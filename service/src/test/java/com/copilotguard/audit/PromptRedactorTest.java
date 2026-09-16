package com.copilotguard.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRedactorTest {

    private final PromptRedactor redactor = new PromptRedactor();

    @Test
    void redactsSecretsAndRecordsHits() {
        String prompt = String.join("\n",
                "api key: sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789",
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def",
                "aws key AKIAIOSFODNN7EXAMPLE here",
                "");

        RedactionResult result = redactor.redact(prompt);

        assertThat(result.redacted()).contains("[REDACTED:anthropic-api-key]");
        assertThat(result.redacted()).contains("[REDACTED:bearer-token]");
        assertThat(result.redacted()).contains("[REDACTED:aws-access-key]");
        assertThat(result.redacted()).doesNotContain("sk-ant-", "AKIA");
        assertThat(result.hits()).containsExactlyInAnyOrder(
                "anthropic-api-key", "bearer-token", "aws-access-key");
    }

    @Test
    void returnsNoHitsForCleanPrompts() {
        RedactionResult result = redactor.redact("a perfectly ordinary prompt about java code");

        assertThat(result.redacted()).isEqualTo("a perfectly ordinary prompt about java code");
        assertThat(result.hits()).isEmpty();
    }
}
