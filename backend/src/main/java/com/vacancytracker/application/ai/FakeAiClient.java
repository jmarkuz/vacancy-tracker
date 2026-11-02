package com.vacancytracker.application.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vacancytracker.application.resume.Terms;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A fixed stand-in for Claude, for tests and the end-to-end test (app.ai.provider=fake). It sends nothing anywhere
 * and answers from the prompt itself, deterministically, so a test can predict every answer and check what reached
 * it ({@link #lastPrompt()}). Never use it for real applications.
 *
 * <p>Adapting a resume adds one bullet naming up to two skills the vacancy asks for and the base resume lacks: a
 * deliberate overreach, so the fact check (T-098) has something to catch and the match visibly rises.
 */
@Component
public class FakeAiClient implements AiClient {

    private static final Pattern FIELD = Pattern.compile("(?m)^(Title|Company|Task): (.+)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private volatile AiPrompt last;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    /** How many times it was asked, for tests that check a cache. */
    public int calls() {
        return calls.get();
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public boolean configured() {
        return true;
    }

    /** The prompt of the last call, for tests. */
    public AiPrompt lastPrompt() {
        return last;
    }

    @Override
    public AiReply complete(AiPrompt prompt) {
        last = prompt;
        calls.incrementAndGet();
        String task = lastTask(prompt.user());
        String text;
        if (task.startsWith("Adapt the base resume")) {
            text = adaptResume(block(prompt.user(), "base_resume"), block(prompt.user(), "vacancy"));
        } else if (task.startsWith("Adapt the base cover letter")) {
            text = adaptLetter(block(prompt.user(), "base_cover_letter"), field(prompt.user(), "Title"), field(prompt.user(), "Company"));
        } else if (task.startsWith("Build the requirement checklist")) {
            text = checklist(block(prompt.user(), "vacancy"), field(prompt.user(), "Title"));
        } else if (task.startsWith("Assess the document against the checklist")) {
            text = statuses(block(prompt.user(), "checklist"), block(prompt.user(), "document"));
        } else if (task.startsWith("Shorten the resume")) {
            text = shorten(block(prompt.user(), "document"));
        } else if (task.startsWith("Rate the vacancy against the goal")) {
            text = goal(block(prompt.user(), "goal"), block(prompt.user(), "vacancy"));
        } else {
            text = "(Fake answer for tests.)\n";
        }
        return new AiReply(text, provider(), "fake-model");
    }

    static String adaptResume(String base, String vacancy) {
        List<String> missing = new ArrayList<>();
        for (String term : Terms.skillLikeTerms(vacancy)) {
            if (!Terms.mentions(base, term) && missing.size() < 2) {
                missing.add(term);
            }
        }
        if (missing.isEmpty()) {
            return base;
        }
        String bullet = "- Built production services with " + String.join(" and ", missing) + ".";
        List<String> lines = new ArrayList<>(List.of(base.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- ")) {
                lines.add(i, bullet);
                return String.join("\n", lines);
            }
        }
        return base + "\n" + bullet;
    }

    static String adaptLetter(String base, String title, String company) {
        String[] paragraphs = base.split("\n\n", 2);
        String opening = "I am applying for the " + title + " role at " + company + ".";
        return paragraphs.length == 2 ? paragraphs[0] + "\n\n" + opening + "\n\n" + paragraphs[1] : opening + "\n\n" + base;
    }

    /**
     * The skill-like terms of the vacancy text as requirements: the first three must-have, the next three important,
     * the rest nice-to-have, at most twelve.
     */
    static String checklist(String vacancy, String title) {
        int body = vacancy.indexOf("\n\n");
        List<String> terms = Terms.skillLikeTerms(body < 0 ? vacancy : vacancy.substring(body));
        ArrayNode requirements = JSON.createArrayNode();
        for (int i = 0; i < Math.min(12, terms.size()); i++) {
            ObjectNode r = requirements.addObject();
            r.put("text", "Experience with " + terms.get(i));
            r.put("priority", i < 3 ? "must" : i < 6 ? "important" : "nice");
            r.putArray("terms").add(terms.get(i));
        }
        if (requirements.isEmpty()) {
            ObjectNode r = requirements.addObject();
            r.put("text", "Experience as " + title);
            r.put("priority", "must");
            r.putArray("terms").add(title);
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("coreProblem", "Deliver the work of a " + title + ".");
        root.set("requirements", requirements);
        return "```json\n" + root.toPrettyString() + "\n```";
    }

    /** Covered, with the document's own words as the quote, when the first term of a requirement is in it; else missing. */
    static String statuses(String checklistJson, String document) {
        ArrayNode items = JSON.createArrayNode();
        try {
            for (JsonNode r : JSON.readTree(checklistJson).path("requirements")) {
                String term = r.path("terms").path(0).asText(r.path("text").asText());
                Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])").matcher(document);
                ObjectNode item = items.addObject();
                item.put("id", r.path("id").asText());
                item.put("status", m.find() ? "covered" : "missing");
                item.put("quote", m.find(0) ? m.group() : "");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The checklist in the prompt is not JSON", e);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("items", items);
        return root.toString();
    }

    /** 70 for every parameter of the goal; a deal-breaker when the vacancy contains the goal's "Deal-breakers:" text. */
    static String goal(String goal, String vacancy) {
        ArrayNode parameters = JSON.createArrayNode();
        String dealBreaker = "";
        for (String line : goal.split("\n")) {
            Matcher m = Pattern.compile("^- (.+): \\d+%$").matcher(line);
            if (m.matches()) {
                ObjectNode p = parameters.addObject();
                p.put("name", m.group(1));
                p.put("score", 70);
                p.put("explanation", "Fake rating for tests.");
            } else if (line.startsWith("Deal-breakers:")) {
                dealBreaker = line.substring("Deal-breakers:".length()).strip();
            }
        }
        boolean found = !dealBreaker.isEmpty() && vacancy.toLowerCase(Locale.ROOT).contains(dealBreaker.toLowerCase(Locale.ROOT));
        ObjectNode root = JSON.createObjectNode();
        root.set("parameters", parameters);
        root.putObject("dealBreaker").put("found", found).put("explanation", found ? "The vacancy says: " + dealBreaker : "");
        return root.toString();
    }

    /** Drops the last 30% of the bullets (of the lines, when there are no bullets): shorter, never different. */
    static String shorten(String resume) {
        List<String> lines = new ArrayList<>(List.of(resume.split("\n", -1)));
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- ")) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                candidates.add(i);
            }
        }
        int drop = (int) Math.ceil(candidates.size() * 0.3);
        for (int k = 0; k < drop; k++) {
            lines.remove((int) candidates.get(candidates.size() - 1 - k));
        }
        return String.join("\n", lines);
    }

    /** The content of the first <tag>…</tag> block, or an empty string. */
    static String block(String text, String tag) {
        int start = text.indexOf("<" + tag + ">\n");
        int end = text.indexOf("\n</" + tag + ">", start + 1);
        return start < 0 || end < 0 ? "" : text.substring(start + tag.length() + 3, end);
    }

    /** The task is the last line of the prompt; a "Task:" line inside the vacancy text is data, not the task. */
    private static String lastTask(String text) {
        String task = "?";
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("Task")) {
                task = m.group(2).trim();
            }
        }
        return task;
    }

    private static String field(String text, String name) {
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return m.group(2).trim();
            }
        }
        return "?";
    }
}
