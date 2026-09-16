package com.copilotguard.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptRedactorTest {

    private final PromptRedactor redactor = new PromptRedactor();

    @Test
    void redactsAwsKeysWithStablePlaceholders() {
        String text = "key1 AKIAIOSFODNN7EXAMPLE and key2 ASIAQWERTYUIOP123456 done";
        RedactionResult result = redactor.redact(text);

        assertThat(result.redacted()).contains("[REDACTED:AWS_KEY:1]", "[REDACTED:AWS_KEY:2]");
        assertThat(result.redacted())
                .doesNotContain("AKIAIOSFODNN7EXAMPLE", "ASIAQWERTYUIOP123456");
        assertThat(result.hits())
                .anySatisfy(
                        hit -> {
                            assertThat(hit.name()).isEqualTo("aws_key");
                            assertThat(hit.blocker()).isTrue();
                        });
    }

    @Test
    void leavesShortAwsLikeStringsUntouched() {
        String text = "id AKIA123 and AKIAIOSFODNN7EX (15 chars)";
        RedactionResult result = redactor.redact(text);

        assertThat(result.redacted()).isEqualTo(text);
    }

    @Test
    void redactsPrivateKeyBlocksButNotPublicKeys() {
        String text =
                "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq...\n-----END PRIVATE KEY-----\n"
                        + "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZ...\n-----END PUBLIC KEY-----";
        RedactionResult result = redactor.redact(text);

        assertThat(result.redacted()).contains("[REDACTED:PRIVATE_KEY:1]");
        assertThat(result.redacted()).contains("-----BEGIN PUBLIC KEY-----");
        assertThat(result.redacted()).doesNotContain("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void redactsJwtsButNotPartialSegments() {
        String text =
                "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c done";
        RedactionResult result = redactor.redact(text);

        assertThat(result.redacted()).contains("[REDACTED:JWT:1]");
        assertThat(result.redacted()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");

        assertThat(redactor.redact("header eyJhbGciOiJIUzI1NiJ9 only").hits()).isEmpty();
    }

    @Test
    void redactsBearerTokensOnlyWithCredentials() {
        RedactionResult result = redactor.redact("Authorization: Bearer abc.def.ghi-jkl");
        assertThat(result.redacted()).contains("[REDACTED:BEARER_TOKEN:1]");

        assertThat(redactor.redact("Authentication: Bearer").hits()).isEmpty();
    }

    @Test
    void redactsConnectionStringsWithCredentialsOnly() {
        RedactionResult result =
                redactor.redact("url: mongodb://admin:hunter2@db.example.com:27017/app");
        assertThat(result.redacted()).contains("[REDACTED:CONNECTION_STRING:1]");
        assertThat(result.redacted()).doesNotContain("hunter2");

        assertThat(redactor.redact("url: mongodb://localhost:27017/app").hits()).isEmpty();
    }

    @Test
    void redactsEmailsButNotVersionLikeStrings() {
        RedactionResult result = redactor.redact("contact dev@example.com and ops+team@corp.io");
        assertThat(result.redacted()).contains("[REDACTED:EMAIL:1]", "[REDACTED:EMAIL:2]");
        assertThat(result.redacted()).doesNotContain("dev@example.com");

        assertThat(redactor.redact("dependency version@2.0.1").hits()).isEmpty();
    }

    @Test
    void redactsSsnShapedNumbersButNotDates() {
        RedactionResult result = redactor.redact("ssn 123-45-6789 recorded");
        assertThat(result.redacted()).contains("[REDACTED:SSN:1]");
        assertThat(result.redacted()).doesNotContain("123-45-6789");

        assertThat(redactor.redact("created 2024-01-15 yesterday").hits()).isEmpty();
    }

    @Test
    void redactsLuhnValidCardsOnly() {
        RedactionResult result =
                redactor.redact("card 4111111111111111 and 4111-1111-1111-1111 stored");
        assertThat(result.redacted()).contains("[REDACTED:CARD:1]", "[REDACTED:CARD:2]");
        assertThat(result.redacted()).doesNotContain("4111");

        assertThat(redactor.redact("id 1234567890123456 invalid").hits()).isEmpty();
    }

    @Test
    void classifiesBlockerHits() {
        RedactionResult blockers = redactor.redact("key AKIAIOSFODNN7EXAMPLE");
        assertThat(blockers.hasBlocker()).isTrue();

        RedactionResult pii = redactor.redact("mail dev@example.com");
        assertThat(pii.hasBlocker()).isFalse();
        assertThat(pii.hits()).extracting(RedactionHit::name).containsExactly("email");
    }
}
