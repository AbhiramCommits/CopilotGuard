package com.copilotguard.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.copilotguard.config.CopilotGuardProperties;
import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AnthropicLlmClientTest {

    @RegisterExtension
    static final WireMockExtension WM =
            WireMockExtension.newInstance()
                    .options(WireMockConfiguration.wireMockConfig().dynamicPort())
                    .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicLlmClient client(
            int maxAttempts, Map<String, CopilotGuardProperties.ModelPrice> prices) {
        CopilotGuardProperties properties =
                new CopilotGuardProperties(
                        "test-key",
                        new CopilotGuardProperties.Anthropic(
                                WM.baseUrl(),
                                "claude-test",
                                "2023-06-01",
                                8192,
                                maxAttempts,
                                Duration.ofSeconds(30),
                                prices),
                        null,
                        null,
                        null);
        return new AnthropicLlmClient(properties, MAPPER);
    }

    private AnthropicLlmClient client(int maxAttempts) {
        return client(
                maxAttempts,
                Map.of(
                        "default",
                        new CopilotGuardProperties.ModelPrice(
                                new BigDecimal("3"), new BigDecimal("15"))));
    }

    @Test
    void generateTestsParsesFilesUsageAndCost() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(testsResponse())));

        TestGenerationResult result = client(3).generateTests("prompt");

        assertThat(result.files()).hasSize(2);
        assertThat(result.files().get(0).path()).isEqualTo("src/test/java/FooTest.java");
        assertThat(result.files().get(0).content()).contains("class FooTest");
        assertThat(result.usage().tokensIn()).isEqualTo(1200);
        assertThat(result.usage().tokensOut()).isEqualTo(800);
        assertThat(result.usage().costUsd()).isEqualByComparingTo(new BigDecimal("0.0156"));
        assertThat(result.model()).isEqualTo("claude-test");
        assertThat(result.rawRequest()).contains("submit_tests");
        assertThat(result.rawResponse()).contains("tool_use");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);

        WM.verify(
                postRequestedFor(urlEqualTo("/v1/messages"))
                        .withHeader(
                                "x-api-key",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                        "test-key")));
    }

    @Test
    void reviewDiffParsesCommentsAndAllowsEmptyFindings() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(reviewResponse(true))));

        ReviewGenerationResult result = client(3).reviewDiff("prompt");
        assertThat(result.comments()).hasSize(2);
        ReviewCommentSuggestion first = result.comments().get(0);
        assertThat(first.file()).isEqualTo("src/main/java/Foo.java");
        assertThat(first.line()).isEqualTo(10);
        assertThat(first.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(first.category()).isEqualTo(CommentCategory.BUG);
        assertThat(first.suggestedFix()).isEqualTo("Guard the divisor");
        assertThat(result.comments().get(1).line()).isNull();
        assertThat(result.comments().get(1).severity()).isEqualTo(Severity.MINOR);

        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(reviewResponse(false))));
        WM.resetRequests();
        assertThat(client(3).reviewDiff("prompt").comments()).isEmpty();
    }

    @Test
    void throwsWhenToolUseBlockMissing() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\":\"msg_1\",\"model\":\"claude-test\","
                                                        + "\"stop_reason\":\"end_turn\","
                                                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                                                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}")));

        assertThatThrownBy(() -> client(3).generateTests("prompt"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("submit_tests");
    }

    @Test
    void throwsOnInvalidEnumValue() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(reviewResponseWithSeverity("CRITICAL"))));

        assertThatThrownBy(() -> client(3).reviewDiff("prompt"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void retriesRateLimitsAndSucceeds() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .inScenario("rate-limit")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(
                                aResponse()
                                        .withStatus(429)
                                        .withHeader("Retry-After", "0")
                                        .withBody(
                                                "{\"error\":{\"type\":\"rate_limit_error\","
                                                        + "\"message\":\"slow down\"}}"))
                        .willSetStateTo("retry-1"));
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .inScenario("rate-limit")
                        .whenScenarioStateIs("retry-1")
                        .willReturn(
                                aResponse()
                                        .withStatus(429)
                                        .withHeader("Retry-After", "0")
                                        .withBody(
                                                "{\"error\":{\"type\":\"rate_limit_error\","
                                                        + "\"message\":\"slow down\"}}"))
                        .willSetStateTo("retry-2"));
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .inScenario("rate-limit")
                        .whenScenarioStateIs("retry-2")
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(testsResponse())));

        assertThat(client(3).generateTests("prompt").files()).hasSize(2);
        WM.verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":{\"type\":\"server_error\"}}")));

        assertThatThrownBy(() -> client(3).generateTests("prompt"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("attempt 3 of 3");
        WM.verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void usesModelSpecificPriceWhenConfigured() {
        WM.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(testsResponse())));

        AnthropicLlmClient priced =
                client(
                        3,
                        Map.of(
                                "default",
                                new CopilotGuardProperties.ModelPrice(
                                        new BigDecimal("3"), new BigDecimal("15")),
                                "claude-test",
                                new CopilotGuardProperties.ModelPrice(
                                        new BigDecimal("10"), new BigDecimal("20"))));

        TestGenerationResult result = priced.generateTests("prompt");
        assertThat(result.usage().costUsd()).isEqualByComparingTo(new BigDecimal("0.028"));
    }

    private static String testsResponse() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_test_1");
        root.put("model", "claude-test");
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "submit_tests");
        ObjectNode input = toolUse.putObject("input");
        ArrayNode files = input.putArray("files");
        files.addObject()
                .put("path", "src/test/java/FooTest.java")
                .put("content", "class FooTest {}");
        files.addObject()
                .put("path", "src/test/java/BarTest.java")
                .put("content", "class BarTest {}");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 1200);
        usage.put("output_tokens", 800);
        return root.toString();
    }

    private static String reviewResponse(boolean withComments) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_review_1");
        root.put("model", "claude-test");
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "submit_review_comments");
        ObjectNode input = toolUse.putObject("input");
        ArrayNode comments = input.putArray("comments");
        if (withComments) {
            ObjectNode first = comments.addObject();
            first.put("file", "src/main/java/Foo.java");
            first.put("line", 10);
            first.put("severity", "BLOCKER");
            first.put("category", "BUG");
            first.put("body", "Division by zero");
            first.put("suggestedFix", "Guard the divisor");
            ObjectNode second = comments.addObject();
            second.put("file", "src/main/java/Foo.java");
            second.put("line", (String) null);
            second.put("severity", "MINOR");
            second.put("category", "STYLE");
            second.put("body", "Rename variable");
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 900);
        usage.put("output_tokens", 300);
        return root.toString();
    }

    private static String reviewResponseWithSeverity(String severity) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_review_2");
        root.put("model", "claude-test");
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "submit_review_comments");
        ObjectNode input = toolUse.putObject("input");
        ObjectNode comment = input.putArray("comments").addObject();
        comment.put("file", "src/main/java/Foo.java");
        comment.put("line", 1);
        comment.put("severity", severity);
        comment.put("category", "BUG");
        comment.put("body", "bad");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 1);
        usage.put("output_tokens", 1);
        return root.toString();
    }
}
