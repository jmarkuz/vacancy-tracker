package com.vacancytracker.application.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for the `claude` executable in {@link ClaudeCliClientTest}: prints its arguments and the prompt it read on
 * standard input, or fails or hangs when the prompt asks it to.
 */
public final class FakeClaudeMain {

    private FakeClaudeMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        if (prompt.contains("FAIL")) {
            System.err.println("Error: not logged in");
            System.exit(3);
        }
        if (prompt.contains("HANG")) {
            Thread.sleep(60_000);
        }
        if (prompt.contains("EMPTY")) {
            return;
        }
        StringBuilder out = new StringBuilder("ARGS");
        for (String a : args) {
            out.append(" [").append(a).append(']');
        }
        out.append("\nKEY ").append(System.getenv("ANTHROPIC_API_KEY") == null ? "absent" : "present");
        out.append("\nPROMPT ").append(prompt);
        System.out.write(out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
