package com.vacancytracker.collection.pipeline;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Progress events of collection runs, for the Refresh button (T-046). A subscriber first receives the
 * events already published, then live ones. The last {@link #KEEP_FINISHED} finished runs are kept in
 * memory; older ones are read from the database by the controller.
 */
@Component
public class RunEventBus {

    static final int KEEP_FINISHED = 20;

    public record RunEvent(String type, Object data) {
    }

    private static final class Channel {
        final List<RunEvent> events = new CopyOnWriteArrayList<>();
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        volatile boolean done;
    }

    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Deque<UUID> finished = new ArrayDeque<>();

    public synchronized void publish(UUID runId, String type, Object data) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        RunEvent event = new RunEvent(type, data);
        channel.events.add(event);
        for (SseEmitter emitter : new ArrayList<>(channel.emitters)) {
            send(channel, emitter, event);
        }
        if ("done".equals(type)) {
            channel.done = true;
            for (SseEmitter emitter : channel.emitters) {
                emitter.complete();
            }
            channel.emitters.clear();
            finished.addLast(runId);
            while (finished.size() > KEEP_FINISHED) {
                channels.remove(finished.removeFirst());
            }
        }
    }

    public boolean knows(UUID runId) {
        return channels.containsKey(runId);
    }

    public SseEmitter subscribe(UUID runId) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (this) {
            for (RunEvent event : channel.events) {
                send(channel, emitter, event);
            }
            if (channel.done) {
                emitter.complete();
            } else {
                channel.emitters.add(emitter);
                emitter.onCompletion(() -> channel.emitters.remove(emitter));
                emitter.onTimeout(() -> channel.emitters.remove(emitter));
            }
        }
        return emitter;
    }

    public List<RunEvent> snapshot(UUID runId) {
        Channel channel = channels.get(runId);
        return channel == null ? List.of() : List.copyOf(channel.events);
    }

    private static void send(Channel channel, SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (IOException e) {
            channel.emitters.remove(emitter);
        }
    }
}
