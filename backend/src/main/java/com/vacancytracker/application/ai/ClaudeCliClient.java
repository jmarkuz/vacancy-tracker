package com.vacancytracker.application.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Claude through the official Claude Code CLI in print mode (`claude -p`), under the owner's own subscription
 * (T-082, ADR-041). The CLI signs in by itself; this class never reads, copies or passes its OAuth token. It also
 * removes ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN from the child's environment, so the subscription is what pays
 * and no key leaves this process.
 *
 * <p>The CLI runs as a plain text generator: no tools, no MCP servers, no slash commands, no saved session, only the
 * settings of an empty temporary folder (so the owner's hooks and plugins do not run), our own system prompt, and the
 * prompt on standard input. Works only where the CLI is installed and signed in, so not inside the Docker image.
 */
@Component
public class ClaudeCliClient implements AiClient {

    static final int MAX_OUTPUT_CHARS = 200_000;

    private final List<String> command;
    private final String model;
    private final long timeoutSeconds;

    public ClaudeCliClient(@Value("${app.ai.cli.command:claude}") String command,
                           @Value("${app.ai.cli.model:}") String model,
                           @Value("${app.ai.cli.timeout-seconds:240}") long timeoutSeconds) {
        this.command = Arrays.stream(command.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.model = model == null ? "" : model.trim();
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    @Override
    public String provider() {
        return "cli";
    }

    @Override
    public boolean configured() {
        return !command.isEmpty();
    }

    /** The full command line: the configured command, then the fixed print-mode flags. */
    List<String> commandLine(String system) {
        List<String> line = new ArrayList<>(command);
        line.addAll(List.of("-p", "--output-format", "text", "--tools", "", "--no-session-persistence", "--strict-mcp-config",
                "--disable-slash-commands", "--setting-sources", "project", "--system-prompt", system));
        if (!model.isEmpty()) {
            line.addAll(List.of("--model", model));
        }
        return line;
    }

    @Override
    public AiReply complete(AiPrompt prompt) {
        Path workDir = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("vt-claude-");
            ProcessBuilder builder = new ProcessBuilder(commandLine(prompt.system())).directory(workDir.toFile());
            Map<String, String> env = builder.environment();
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_AUTH_TOKEN");
            process = builder.start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(running.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(running.getErrorStream()));
            try (OutputStream in = process.getOutputStream()) {
                in.write(prompt.user().getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AiFailedException("Claude (CLI) did not answer within " + timeoutSeconds + " seconds.");
            }
            String out = stdout.get(5, TimeUnit.SECONDS).strip();
            if (process.exitValue() != 0) {
                String err = stderr.get(5, TimeUnit.SECONDS).strip();
                throw new AiFailedException("Claude (CLI) failed with exit code " + process.exitValue()
                        + (err.isEmpty() ? "" : ": " + firstLine(err)) + ". Check that `claude` is installed and signed in.");
            }
            if (out.isEmpty()) {
                throw new AiFailedException("Claude (CLI) returned an empty answer.");
            }
            return new AiReply(out, provider(), model.isEmpty() ? "claude-cli" : model);
        } catch (IOException e) {
            throw new AiFailedException("Could not start the Claude CLI (" + String.join(" ", command)
                    + "): install Claude Code and sign in, or set APP_AI_CLI_COMMAND.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiFailedException("Interrupted while waiting for Claude (CLI).");
        } catch (ExecutionException | TimeoutException e) {
            throw new AiFailedException("Could not read the answer of Claude (CLI).");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(workDir);
        }
    }

    private static String read(InputStream stream) {
        try (InputStream in = stream) {
            byte[] bytes = in.readNBytes(MAX_OUTPUT_CHARS * 4);
            in.transferTo(OutputStream.nullOutputStream());
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > MAX_OUTPUT_CHARS ? text.substring(0, MAX_OUTPUT_CHARS) : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String firstLine(String text) {
        String line = text.lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A leftover empty temp folder is harmless.
        }
    }
}
