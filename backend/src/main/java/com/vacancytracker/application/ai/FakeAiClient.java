package com.vacancytracker.application.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A fixed stand-in for Claude, for tests and the end-to-end test (app.ai.provider=fake). It sends nothing anywhere
 * and answers from the prompt itself, so a test can check what reached it. Never use it for real applications.
 */
@Component
public class FakeAiClient implements AiClient {

    private static final Pattern FIELD = Pattern.compile("(?m)^(Title|Company|Task): (.+)$");

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public AiReply complete(AiPrompt prompt) {
        String title = field(prompt.user(), "Title");
        String company = field(prompt.user(), "Company");
        String task = field(prompt.user(), "Task");
        String text = task.startsWith("Write the cover letter")
                ? "Dear " + company + " team,\n\nI am applying for the " + title + " position.\n\n(Fake cover letter for tests.)\n"
                : "# Tailored resume for " + title + " at " + company + "\n\n(Fake resume for tests.)\n";
        return new AiReply(text, provider(), "fake-model");
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
