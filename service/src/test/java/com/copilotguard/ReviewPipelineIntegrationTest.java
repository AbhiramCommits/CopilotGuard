package com.copilotguard;

import com.copilotguard.api.AuditTrailResponse;
import com.copilotguard.api.ReviewRequest;
import com.copilotguard.api.ReviewResponse;
import com.copilotguard.audit.PromptAudit;
import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.CompileStatus;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.PassStatus;
import com.copilotguard.domain.ReviewComment;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.ReviewRunStatus;
import com.copilotguard.domain.Severity;
import com.copilotguard.domain.ValidationStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReviewPipelineIntegrationTest {

    private static final String MODEL = "claude-sonnet-4-20250514";

    private static final String SAMPLE_DIFF = String.join("\n",
            "diff --git a/src/main/java/com/example/Calculator.java b/src/main/java/com/example/Calculator.java",
            "index 7f8a2b1..9c3d4e5 100644",
            "--- a/src/main/java/com/example/Calculator.java",
            "+++ b/src/main/java/com/example/Calculator.java",
            "@@ -1,6 +1,7 @@",
            " package com.example;",
            " ",
            " public class Calculator {",
            "+    public int add(int a, int b) { return a + b; }",
            "+    // author: dev@example.com",
            "     public int subtract(int a, int b) { return a - b; }",
            " }");

    private static final String DIFF_WITH_AWS_KEY = String.join("\n",
            "diff --git a/src/main/java/com/example/Calculator.java b/src/main/java/com/example/Calculator.java",
            "index 7f8a2b1..9c3d4e5 100644",
            "--- a/src/main/java/com/example/Calculator.java",
            "+++ b/src/main/java/com/example/Calculator.java",
            "@@ -1,6 +1,7 @@",
            " package com.example;",
            " ",
            " public class Calculator {",
            "+    public int add(int a, int b) { return a + b; }",
            "+    // legacy key AKIAIOSFODNN7EXAMPLE",
            "     public int subtract(int a, int b) { return a - b; }",
            " }");

    private static final String CONVENTIONS_YAML = String.join("\n",
            "naming:",
            "  testClassNamePattern: \".*(Test|IT)$\"",
            "bannedApis:",
            "  - \"Thread\\\\.sleep\"",
            "requiredTestAnnotations:",
            "  - org.junit.jupiter.api.Test",
            "maxMethodLength: 50",
            "");

    private static final String COMPLIANT_TEST = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "class CalculatorTest {",
            "",
            "    @Test",
            "    void adds() {",
            "        org.junit.jupiter.api.Assertions.assertEquals(4, new Calculator().add(2, 2));",
            "    }",
            "}",
            "");

    private static final String TEST_WITH_THREAD_SLEEP = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "class CalculatorIT {",
            "",
            "    @Test",
            "    void slowPath() throws Exception {",
            "        Thread.sleep(100);",
            "    }",
            "}",
            "");

    private static final String TEST_WITH_SYSTEM_EXIT = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "class CalculatorIT {",
            "",
            "    @Test",
            "    void exitPath() {",
            "        System.exit(0);",
            "    }",
            "}",
            "");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        registry.add("copilotguard.anthropic.base-url", WM::baseUrl);
        registry.add("copilotguard.anthropic-api-key", () -> "test-key");
        registry.add("copilotguard.github.base-url", WM::baseUrl);
        registry.add("copilotguard.github.raw-base-url", WM::baseUrl);
        registry.add("copilotguard.github.token", () -> "");
        registry.add("copilotguard.validation.junit-console-jar", ReviewPipelineIntegrationTest::junitConsoleJar);
    }

    private static String junitConsoleJar() {
        return System.getProperty("user.home")
                + "/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.10.5/"
                + "junit-platform-console-standalone-1.10.5.jar";
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ReviewRunRepository reviewRunRepository;

    @Autowired
    GeneratedTestRepository generatedTestRepository;

    @Autowired
    ReviewCommentRepository reviewCommentRepository;

    @Autowired
    PromptAuditRepository promptAuditRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws JsonProcessingException {
        WM.resetAll();
        stubAnthropic(
                testGenResponse(
                        "src/test/java/com/example/CalculatorTest.java", COMPLIANT_TEST,
                        "src/test/java/com/example/CalculatorIT.java", TEST_WITH_THREAD_SLEEP),
                reviewResponse());
        reviewCommentRepository.deleteAll();
        generatedTestRepository.deleteAll();
        reviewRunRepository.deleteAll();
        promptAuditRepository.deleteAll();
    }

    @Test
    void rawDiffHappyPathPersistsEverythingAndRejectsUnvalidatedTests() {
        ResponseEntity<ReviewResponse> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(SAMPLE_DIFF, null, null, null, CONVENTIONS_YAML, false), ReviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("SUCCEEDED");
        assertThat(body.repo()).isEqualTo("local");
        assertThat(body.promptTemplateId()).isEqualTo("generate_tests:v1");
        assertThat(body.generatedTests()).hasSize(2);
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("CalculatorTest");
            assertThat(test.validationStatus()).isEqualTo("COMPILE_FAIL");
            assertThat(test.accepted()).isFalse();
            assertThat(test.content()).isNull();
            assertThat(test.compileStatus()).isEqualTo("FAILURE");
            assertThat(test.validationDetail()).isNotBlank();
        });
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("CalculatorIT");
            assertThat(test.validationStatus()).isEqualTo("PASSING");
            assertThat(test.accepted()).isTrue();
            assertThat(test.content()).contains("class CalculatorIT");
        });
        assertThat(body.comments()).hasSize(3);
        assertThat(body.tokenInput()).isEqualTo(2100);
        assertThat(body.tokenOutput()).isEqualTo(1100);
        assertThat(body.costUsd()).isEqualByComparingTo(new BigDecimal("0.0228"));

        ReviewRun run = reviewRunRepository.findAll().get(0);
        assertThat(run.getStatus()).isEqualTo(ReviewRunStatus.SUCCEEDED);

        List<GeneratedTest> tests = generatedTestRepository.findAll();
        assertThat(tests).hasSize(2);
        assertThat(tests).anySatisfy(test -> {
            assertThat(test.getFilePath()).contains("CalculatorTest");
            assertThat(test.getCompileStatus()).isEqualTo(CompileStatus.FAILURE);
            assertThat(test.getPassStatus()).isEqualTo(PassStatus.PENDING);
            assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.COMPILE_FAIL);
        });
        assertThat(tests).anySatisfy(test -> {
            assertThat(test.getFilePath()).contains("CalculatorIT");
            assertThat(test.getCompileStatus()).isEqualTo(CompileStatus.SUCCESS);
            assertThat(test.getPassStatus()).isEqualTo(PassStatus.PASSED);
            assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.PASSING);
        });

        List<ReviewComment> comments = reviewCommentRepository.findAll();
        assertThat(comments).hasSize(3);
        assertThat(comments).anySatisfy(comment -> {
            assertThat(comment.getCategory()).isEqualTo(CommentCategory.CONVENTIONS);
            assertThat(comment.getBody()).contains("banned API");
        });

        List<PromptAudit> audits = promptAuditRepository.findAll();
        assertThat(audits).hasSize(2);
        assertThat(audits).extracting(PromptAudit::getTemplateId)
                .containsExactlyInAnyOrder("generate_tests", "review_diff");
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.getRedactionHits()).contains("email");
            assertThat(audit.getRedactedPrompt()).contains("[REDACTED:EMAIL:1]");
            assertThat(audit.getRedactedPrompt()).doesNotContain("dev@example.com");
        });

        WM.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void auditEndpointReturnsTrailAndVerdicts() {
        restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(SAMPLE_DIFF, null, null, null, null, false), ReviewResponse.class);

        long runId = reviewRunRepository.findAll().get(0).getId();
        ResponseEntity<AuditTrailResponse> auditResponse =
                restTemplate.getForEntity("/api/v1/reviews/" + runId + "/audit", AuditTrailResponse.class);

        assertThat(auditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuditTrailResponse trail = auditResponse.getBody();
        assertThat(trail).isNotNull();
        assertThat(trail.runId()).isEqualTo(runId);
        assertThat(trail.status()).isEqualTo("SUCCEEDED");
        assertThat(trail.audits()).hasSize(2);
        assertThat(trail.audits()).allSatisfy(entry -> {
            assertThat(entry.redactedPrompt()).doesNotContain("dev@example.com");
            assertThat(entry.redactionHits()).contains("email");
            assertThat(entry.templateVersion()).isEqualTo("v1");
        });
        assertThat(trail.verdicts()).hasSize(2);
        assertThat(trail.verdicts()).extracting(AuditTrailResponse.VerdictEntry::validationStatus)
                .containsExactlyInAnyOrder("COMPILE_FAIL", "PASSING");
    }

    @Test
    void auditEndpointReturns404ForUnknownRun() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/reviews/999999/audit", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blockerSecretFailsRunUnlessAllowRedactedSend() {
        ResponseEntity<String> blocked = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(DIFF_WITH_AWS_KEY, null, null, null, null, false), String.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody()).contains("aws_key", "allowRedactedSend");

        ReviewRun failedRun = reviewRunRepository.findAll().get(0);
        assertThat(failedRun.getStatus()).isEqualTo(ReviewRunStatus.FAILED);

        List<PromptAudit> blockedAudits = promptAuditRepository.findAll();
        assertThat(blockedAudits).hasSize(1);
        PromptAudit blockedAudit = blockedAudits.get(0);
        assertThat(blockedAudit.getRedactionHits()).contains("aws_key");
        assertThat(blockedAudit.getRedactedPrompt()).contains("[REDACTED:AWS_KEY:1]");
        assertThat(blockedAudit.getRedactedPrompt()).doesNotContain("AKIAIOSFODNN7EXAMPLE");

        WM.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void blockerSecretIsRedactedAndSentWhenAllowed() {
        ResponseEntity<ReviewResponse> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(DIFF_WITH_AWS_KEY, null, null, null, null, true), ReviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("SUCCEEDED");

        List<PromptAudit> audits = promptAuditRepository.findAll();
        assertThat(audits).hasSize(2);
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.getRedactionHits()).contains("aws_key");
            assertThat(audit.getRedactedPrompt()).contains("[REDACTED:AWS_KEY:1]");
            assertThat(audit.getRedactedPrompt()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        });
    }

    @Test
    void retriesRateLimitedReviewCallWithBackoff() throws JsonProcessingException {
        WM.resetAll();
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_tests")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(testGenResponse(
                                "src/test/java/com/example/CalculatorTest.java", COMPLIANT_TEST,
                                "src/test/java/com/example/CalculatorIT.java", COMPLIANT_TEST))));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
                .willSetStateTo("retry-1"));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .inScenario("rate-limit")
                .whenScenarioStateIs("retry-1")
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
                .willSetStateTo("retry-2"));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .inScenario("rate-limit")
                .whenScenarioStateIs("retry-2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(reviewResponse())));
        reviewCommentRepository.deleteAll();
        generatedTestRepository.deleteAll();
        reviewRunRepository.deleteAll();
        promptAuditRepository.deleteAll();

        ResponseEntity<ReviewResponse> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(SAMPLE_DIFF, null, null, null, null, false), ReviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");

        WM.verify(3, postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments"))));
    }

    @Test
    void githubPrPathFetchesDiffMetaConventionsAndValidatesAgainstClone() throws Exception {
        LocalRepo repo = createLocalRepo();
        WM.resetAll();
        WM.stubFor(get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"base\":{\"sha\":\"" + repo.sha() + "\"},\"head\":{\"sha\":\"" + repo.sha()
                                + "\",\"repo\":{\"clone_url\":\"file://" + repo.dir() + "\"}}}")));
        WM.stubFor(get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                .withHeader("Accept", equalTo("application/vnd.github.v3.diff"))
                .willReturn(aResponse().withBody(SAMPLE_DIFF)));
        WM.stubFor(get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                .willReturn(aResponse().withBody(String.join("\n",
                        "naming:",
                        "  testClassNamePattern: \".*(Test|IT)$\"",
                        "bannedApis:",
                        "  - \"System\\\\.exit\"",
                        "requiredTestAnnotations:",
                        "  - org.junit.jupiter.api.Test",
                        "maxMethodLength: 50",
                        ""))));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_tests")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(testGenResponse(
                                "src/test/java/com/example/CalculatorTest.java", COMPLIANT_TEST,
                                "src/test/java/com/example/CalculatorIT.java", TEST_WITH_SYSTEM_EXIT))));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(reviewResponse())));
        reviewCommentRepository.deleteAll();
        generatedTestRepository.deleteAll();
        reviewRunRepository.deleteAll();
        promptAuditRepository.deleteAll();

        ResponseEntity<ReviewResponse> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(null, "acme", "widgets", 7, null, false), ReviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.repo()).isEqualTo("acme/widgets");
        assertThat(body.baseSha()).isEqualTo(repo.sha());
        assertThat(body.headSha()).isEqualTo(repo.sha());

        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("CalculatorTest");
            assertThat(test.validationStatus()).isEqualTo("PASSING");
            assertThat(test.accepted()).isTrue();
            assertThat(test.content()).contains("class CalculatorTest");
        });
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("CalculatorIT");
            assertThat(test.accepted()).isFalse();
        });

        ReviewRun run = reviewRunRepository.findAll().get(0);
        assertThat(run.getRepo()).isEqualTo("acme/widgets");
        assertThat(run.getStatus()).isEqualTo(ReviewRunStatus.SUCCEEDED);

        assertThat(reviewCommentRepository.findAll()).anySatisfy(comment -> {
            assertThat(comment.getCategory()).isEqualTo(CommentCategory.CONVENTIONS);
            assertThat(comment.getBody()).contains("banned API");
        });

        WM.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("System\\.exit"))));
    }

    @Test
    void rejectsRequestWithoutDiffOrPrReference() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(null, null, null, null, null, false), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("provide either 'diff' or");
    }

    private static LocalRepo createLocalRepo() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("copilotguard-fixture-repo");
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/Calculator.java"), String.join("\n",
                "package com.example;",
                "",
                "public class Calculator {",
                "    public int add(int a, int b) { return a + b; }",
                "}",
                ""));
        run("git", "init", dir.toString());
        run("git", "-C", dir.toString(), "add", ".");
        run("git", "-C", dir.toString(), "-c", "user.email=test@example.com",
                "-c", "user.name=Test", "commit", "-m", "initial");
        String sha = capture("git", "-C", dir.toString(), "rev-parse", "HEAD").strip();
        return new LocalRepo(dir, sha);
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
    }

    private static String capture(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
        return output;
    }

    private record LocalRepo(Path dir, String sha) {
    }

    private void stubAnthropic(String testsBody, String reviewBody) {
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_tests")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(testsBody)));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(reviewBody)));
    }

    private String testGenResponse(String path1, String content1, String path2, String content2)
            throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "msg_test_1");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", MODEL);
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "toolu_1");
        toolUse.put("name", "submit_tests");
        ObjectNode input = toolUse.putObject("input");
        ArrayNode files = input.putArray("files");
        files.addObject().put("path", path1).put("content", content1);
        files.addObject().put("path", path2).put("content", content2);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 1200);
        usage.put("output_tokens", 800);
        return objectMapper.writeValueAsString(root);
    }

    private String reviewResponse() throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "msg_review_1");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", MODEL);
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "toolu_2");
        toolUse.put("name", "submit_review_comments");
        ObjectNode input = toolUse.putObject("input");
        ArrayNode comments = input.putArray("comments");
        ObjectNode first = comments.addObject();
        first.put("file", "src/main/java/com/example/Calculator.java");
        first.put("line", 10);
        first.put("severity", "BLOCKER");
        first.put("category", "BUG");
        first.put("body", "Division by zero when divisor is null");
        first.put("suggestedFix", "Guard the divisor against zero");
        ObjectNode second = comments.addObject();
        second.put("file", "src/main/java/com/example/Calculator.java");
        second.put("line", 25);
        second.put("severity", "MINOR");
        second.put("category", "STYLE");
        second.put("body", "Variable name is unclear");
        second.put("suggestedFix", "Rename to 'remaining'");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 900);
        usage.put("output_tokens", 300);
        return objectMapper.writeValueAsString(root);
    }
}
