package com.copilotguard.llm;

import com.copilotguard.config.CopilotGuardProperties;
import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class AnthropicLlmClient implements LlmClient {

    private static final String SYSTEM_PROMPT = "You are CopilotGuard, an expert test author and code reviewer.";
    private static final String TEST_TOOL = "submit_tests";
    private static final String REVIEW_TOOL = "submit_review_comments";

    private final RestClient restClient;
    private final CopilotGuardProperties properties;
    private final ObjectMapper objectMapper;

    public AnthropicLlmClient(CopilotGuardProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        CopilotGuardProperties.Anthropic anthropic = properties.anthropic();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) anthropic.timeout().toMillis());
        requestFactory.setReadTimeout((int) anthropic.timeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(anthropic.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", properties.anthropicApiKey())
                .defaultHeader("anthropic-version", anthropic.version())
                .build();
    }

    @Override
    public TestGenerationResult generateTests(String prompt) {
        LlmExchange exchange = exchange(prompt, testsTool(), TEST_TOOL);
        JsonNode filesNode = exchange.toolInput().path("files");
        if (!filesNode.isArray()) {
            throw new LlmException("tool input must contain a 'files' array");
        }
        List<GeneratedTestFile> files = new ArrayList<>();
        for (JsonNode fileNode : filesNode) {
            files.add(new GeneratedTestFile(requireText(fileNode, "path"), requireText(fileNode, "content")));
        }
        if (files.isEmpty()) {
            throw new LlmException("model returned no test files");
        }
        return new TestGenerationResult(List.copyOf(files), exchange.usage(), exchange.rawRequest(),
                exchange.rawResponse(), exchange.latencyMs(), exchange.model());
    }

    @Override
    public ReviewGenerationResult reviewDiff(String prompt) {
        LlmExchange exchange = exchange(prompt, reviewTool(), REVIEW_TOOL);
        JsonNode commentsNode = exchange.toolInput().path("comments");
        if (!commentsNode.isArray()) {
            throw new LlmException("tool input must contain a 'comments' array");
        }
        List<ReviewCommentSuggestion> comments = new ArrayList<>();
        for (JsonNode commentNode : commentsNode) {
            comments.add(parseComment(commentNode));
        }
        if (comments.isEmpty()) {
            throw new LlmException("model returned no review comments");
        }
        return new ReviewGenerationResult(List.copyOf(comments), exchange.usage(), exchange.rawRequest(),
                exchange.rawResponse(), exchange.latencyMs(), exchange.model());
    }

    private ReviewCommentSuggestion parseComment(JsonNode node) {
        String file = requireText(node, "file");
        Integer line = node.path("line").canConvertToInt() ? node.path("line").asInt() : null;
        Severity severity = parseEnum(node, "severity", Severity.class);
        CommentCategory category = parseEnum(node, "category", CommentCategory.class);
        String body = requireText(node, "body");
        String suggestedFix = node.path("suggestedFix").isTextual() ? node.path("suggestedFix").asText() : null;
        return new ReviewCommentSuggestion(file, line, severity, category, body, suggestedFix);
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode node, String field, Class<E> type) {
        String value = node.path(field).asText(null);
        if (value == null) {
            throw new LlmException("comment field '" + field + "' is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new LlmException("invalid value '" + value + "' for field '" + field + "'");
        }
    }

    private static String requireText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new LlmException("field '" + field + "' is required");
        }
        return value;
    }

    private LlmExchange exchange(String prompt, JsonNode tool, String toolName) {
        String requestBody = buildRequest(prompt, tool, toolName);
        CopilotGuardProperties.Anthropic anthropic = properties.anthropic();
        long start = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                String responseBody = restClient.post()
                        .uri("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return parse(responseBody, toolName, requestBody, elapsedMillis(start));
            } catch (RestClientResponseException | ResourceAccessException ex) {
                if (attempt >= anthropic.maxAttempts() || !isRetryable(ex)) {
                    throw new LlmException("Anthropic API call failed on attempt " + attempt + " of "
                            + anthropic.maxAttempts() + ": " + describe(ex), ex);
                }
                sleep(backoffMillis(ex, attempt));
            }
        }
    }

    private static boolean isRetryable(RuntimeException ex) {
        if (ex instanceof RestClientResponseException restClientEx) {
            int status = restClientEx.getStatusCode().value();
            return status == 429 || status == 529 || status >= 500;
        }
        return ex instanceof ResourceAccessException;
    }

    private static long backoffMillis(RuntimeException ex, int attempt) {
        if (ex instanceof RestClientResponseException restClientEx) {
            String retryAfter = restClientEx.getResponseHeaders().getFirst("Retry-After");
            if (retryAfter != null) {
                try {
                    return Math.min(Long.parseLong(retryAfter), 60) * 1000;
                } catch (NumberFormatException ignored) {
                    // fall through to exponential backoff
                }
            }
        }
        return Math.min(1000L << Math.min(attempt - 1, 3), 8000L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted while waiting to retry Anthropic API call", ex);
        }
    }

    private static String describe(RuntimeException ex) {
        if (ex instanceof RestClientResponseException restClientEx) {
            return restClientEx.getStatusCode() + " " + restClientEx.getStatusText();
        }
        return ex.getMessage();
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private String buildRequest(String prompt, JsonNode tool, String toolName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.anthropic().model());
        body.put("max_tokens", properties.anthropic().maxTokens());
        body.put("system", SYSTEM_PROMPT);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);
        body.set("tools", objectMapper.createArrayNode().add(tool));
        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", toolName);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new LlmException("failed to serialize Anthropic request", ex);
        }
    }

    private LlmExchange parse(String responseBody, String expectedTool, String requestBody, long latencyMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException ex) {
            throw new LlmException("unparseable Anthropic response", ex);
        }
        JsonNode toolInput = null;
        for (JsonNode block : root.path("content")) {
            if (expectedTool.equals(block.path("name").asText())
                    && "tool_use".equals(block.path("type").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode()) {
            throw new LlmException("expected tool_use block '" + expectedTool + "' not found in response");
        }
        JsonNode usage = root.path("usage");
        int tokensIn = usage.path("input_tokens").asInt(0);
        int tokensOut = usage.path("output_tokens").asInt(0);
        String model = root.path("model").asText(properties.anthropic().model());
        return new LlmExchange(toolInput, requestBody, responseBody, model, latencyMs,
                new LlmUsage(tokensIn, tokensOut, costUsd(model, tokensIn, tokensOut)));
    }

    private BigDecimal costUsd(String model, int tokensIn, int tokensOut) {
        CopilotGuardProperties.ModelPrice price = properties.anthropic().prices().get(model);
        if (price == null) {
            price = properties.anthropic().prices().get("default");
        }
        if (price == null) {
            throw new LlmException("no price configured for model '" + model + "' or 'default'");
        }
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return price.inputPerMTok().multiply(BigDecimal.valueOf(tokensIn))
                .divide(million, 6, RoundingMode.HALF_UP)
                .add(price.outputPerMTok().multiply(BigDecimal.valueOf(tokensOut))
                        .divide(million, 6, RoundingMode.HALF_UP));
    }

    private JsonNode testsTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", TEST_TOOL);
        tool.put("description", "Submit generated JUnit 5 test source files");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        schema.set("required", objectMapper.createArrayNode().add("files"));
        ObjectNode properties = schema.putObject("properties");
        ObjectNode files = properties.putObject("files");
        files.put("type", "array");
        ObjectNode items = files.putObject("items");
        items.put("type", "object");
        items.set("required", objectMapper.createArrayNode().add("path").add("content"));
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("path").put("type", "string");
        itemProps.putObject("content").put("type", "string");
        return tool;
    }

    private JsonNode reviewTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", REVIEW_TOOL);
        tool.put("description", "Submit structured review comments");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        schema.set("required", objectMapper.createArrayNode().add("comments"));
        ObjectNode properties = schema.putObject("properties");
        ObjectNode comments = properties.putObject("comments");
        comments.put("type", "array");
        ObjectNode items = comments.putObject("items");
        items.put("type", "object");
        items.set("required", objectMapper.createArrayNode()
                .add("file").add("severity").add("category").add("body"));
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("file").put("type", "string");
        itemProps.putArray("line").add("integer").add("null");
        ObjectNode severity = itemProps.putObject("severity");
        severity.put("type", "string");
        severity.set("enum", objectMapper.createArrayNode().add("BLOCKER").add("MAJOR").add("MINOR"));
        ObjectNode category = itemProps.putObject("category");
        category.put("type", "string");
        category.set("enum", objectMapper.createArrayNode()
                .add("BUG").add("SECURITY").add("PERFORMANCE")
                .add("STYLE").add("TESTING").add("READABILITY"));
        itemProps.putObject("body").put("type", "string");
        itemProps.putObject("suggestedFix").put("type", "string");
        return tool;
    }

    private record LlmExchange(
            JsonNode toolInput,
            String rawRequest,
            String rawResponse,
            String model,
            long latencyMs,
            LlmUsage usage) {
    }
}
