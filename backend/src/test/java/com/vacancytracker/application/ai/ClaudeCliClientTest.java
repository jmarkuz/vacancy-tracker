package com.vacancytracker.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.application.ai.AiClient.AiPrompt;
import org.junit.jupiter.api.Test;

/** The CLI adapter against a stand-in process (the JVM running {@link FakeClaudeMain}), so no real Claude is called. */
class ClaudeCliClientTest {

    private static String fakeCommand() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return String.join(",", java, "-cp", System.getProperty("java.class.path"), FakeClaudeMain.class.getName());
    }

    @Test
    void runsPrintModeWithoutToolsSettingsOrSessionAndSendsThePromptOnStandardInput() {
        ClaudeCliClient cli = new ClaudeCliClient(fakeCommand(), "sonnet", 60);

        AiClient.AiReply reply = cli.complete(new AiPrompt("Be brief.", "Title: Engineer\nТекст українською", 100));

        assertThat(reply.provider()).isEqualTo("cli");
        assertThat(reply.model()).isEqualTo("sonnet");
        assertThat(reply.text())
                .contains("[-p] [--output-format] [text] [--tools] [] [--no-session-persistence] [--strict-mcp-config]")
                .contains("[--disable-slash-commands] [--setting-sources] [project] [--system-prompt] [Be brief.] [--model] [sonnet]")
                .contains("KEY absent")
                .contains("PROMPT Title: Engineer\nТекст українською");
    }

    @Test
    void failuresBecomeMessagesForTheOwner() {
        ClaudeCliClient cli = new ClaudeCliClient(fakeCommand(), "", 60);

        assertThatThrownBy(() -> cli.complete(new AiPrompt("s", "FAIL", 10)))
                .isInstanceOf(AiFailedException.class)
                .hasMessageContaining("exit code 3")
                .hasMessageContaining("not logged in");
        assertThatThrownBy(() -> cli.complete(new AiPrompt("s", "EMPTY", 10)))
                .isInstanceOf(AiFailedException.class).hasMessageContaining("empty answer");
        assertThatThrownBy(() -> new ClaudeCliClient("no-such-claude-binary-xyz", "", 60).complete(new AiPrompt("s", "u", 10)))
                .isInstanceOf(AiFailedException.class).hasMessageContaining("Could not start the Claude CLI");
    }

    @Test
    void aHangingCliIsStoppedAtTheTimeout() {
        ClaudeCliClient cli = new ClaudeCliClient(fakeCommand(), "", 5);

        assertThatThrownBy(() -> cli.complete(new AiPrompt("s", "HANG", 10)))
                .isInstanceOf(AiFailedException.class).hasMessageContaining("within 5 seconds");
    }

    @Test
    void anEmptyCommandIsNotConfigured() {
        assertThat(new ClaudeCliClient(" , ", "", 60).configured()).isFalse();
        assertThat(new ClaudeCliClient("claude", "", 60).configured()).isTrue();
    }
}
