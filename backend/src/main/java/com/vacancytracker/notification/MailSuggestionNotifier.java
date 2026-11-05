package com.vacancytracker.notification;

import com.vacancytracker.common.MailSuggestionsEvent;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One Telegram message after a mailbox read that stored mails with a pending status suggestion (M8). Off unless the
 * Telegram digest is set up (same token and chat) and {@code app.notifications.telegram.mail-suggestions} is true.
 * The text is plain (no Telegram markup, like the digest), so nothing needs escaping; each field is cut to one short
 * line. It holds only the category, vacancy title, company and suggested status; the mail subject is used only when
 * the mail is not linked to a known vacancy. Mail text is never included. The send runs in its own thread, so a slow
 * or failing Telegram never delays or fails the mail read.
 */
@Service
public class MailSuggestionNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(MailSuggestionNotifier.class);
    static final int MAX_LINES = 10;
    static final int MAX_FIELD = 80;

    /** The vacancy a mail is linked to. */
    record Vacancy(String title, String company) {
    }

    /** Finds the vacancies for the ids of the linked mails. */
    @FunctionalInterface
    interface Lookup {
        Map<UUID, Vacancy> find(Collection<UUID> ids);
    }

    private final TelegramDigestService telegram;
    private final Lookup lookup;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-mail-suggestions");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public MailSuggestionNotifier(TelegramDigestService telegram, JdbcClient jdbc,
                                  @Value("${app.notifications.telegram.mail-suggestions:false}") boolean enabled) {
        this(telegram, (Lookup) ids -> vacancies(jdbc, ids), enabled);
    }

    MailSuggestionNotifier(TelegramDigestService telegram, Lookup lookup, boolean enabled) {
        this.telegram = telegram;
        this.lookup = lookup;
        this.enabled = enabled;
    }

    private static Map<UUID, Vacancy> vacancies(JdbcClient jdbc, Collection<UUID> ids) {
        Map<UUID, Vacancy> found = new HashMap<>();
        jdbc.sql("SELECT id, title, company FROM vacancy WHERE id IN (:ids)").param("ids", ids)
                .query((rs, i) -> found.put(rs.getObject("id", UUID.class), new Vacancy(rs.getString("title"), rs.getString("company"))))
                .list();
        return found;
    }

    boolean isEnabled() {
        return enabled && telegram.isEnabled();
    }

    @EventListener
    void onSuggestions(MailSuggestionsEvent event) {
        if (!isEnabled() || event.items().isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException e) {
            LOG.info("Mail suggestions: not sent, the application is shutting down");
        }
    }

    /** Builds and sends the one message for an event. Never throws; returns whether Telegram accepted it. */
    boolean send(MailSuggestionsEvent event) {
        try {
            if (!isEnabled() || event.items().isEmpty()) {
                return false;
            }
            List<UUID> ids = event.items().stream().map(MailSuggestionsEvent.Item::vacancyId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<UUID, Vacancy> found = ids.isEmpty() ? Map.of() : lookup.find(ids);
            String error = telegram.post(message(event.items(), found));
            if (error != null) {
                LOG.warn("Mail suggestions not sent: {}", error);
            }
            return error == null;
        } catch (DataAccessException | IllegalStateException | IllegalArgumentException e) {
            // Only the type: the text of a database or HTTP error can carry addresses or credentials.
            LOG.warn("Mail suggestions not sent: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    static String message(List<MailSuggestionsEvent.Item> items, Map<UUID, Vacancy> vacancies) {
        List<MailSuggestionsEvent.Item> sorted = items.stream()
                .sorted(Comparator.comparingInt(i -> rank(i.category()))).toList();
        StringBuilder out = new StringBuilder("📬 ").append(items.size())
                .append(items.size() == 1 ? " mail needs a decision" : " mails need a decision");
        for (MailSuggestionsEvent.Item item : sorted.subList(0, Math.min(sorted.size(), MAX_LINES))) {
            Vacancy v = item.vacancyId() == null ? null : vacancies.get(item.vacancyId());
            String what;
            if (v != null) {
                what = cut(v.title(), MAX_FIELD) + (blank(v.company()) ? "" : " at " + cut(v.company(), MAX_FIELD));
            } else {
                what = cut(item.subject(), MAX_FIELD);
            }
            out.append("\n• ").append(label(item.category())).append(" — ").append(what.isEmpty() ? "(no subject)" : what)
                    .append(": move to ").append(cut(item.status(), MAX_FIELD)).append('?');
        }
        if (sorted.size() > MAX_LINES) {
            out.append("\n…and ").append(sorted.size() - MAX_LINES).append(" more");
        }
        return out.toString();
    }

    /** Offers first, then interviews, then rejections; the rest after, in the order they were stored. */
    private static int rank(String category) {
        return switch (category == null ? "" : category) {
            case "offer" -> 0;
            case "interview" -> 1;
            case "rejection" -> 2;
            default -> 3;
        };
    }

    private static String label(String category) {
        if (blank(category)) {
            return "Mail";
        }
        String text = category.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** One line, control characters removed, at most max characters. */
    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max - 1).trim() + "…";
    }
}
