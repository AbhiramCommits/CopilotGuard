package com.copilotguard;

import com.copilotguard.api.AuditTrailResponse;
import com.copilotguard.api.ReviewRequest;
import com.copilotguard.api.ReviewResponse;
import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRunRepository;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ValidationGateIntegrationTest {

    private static final String MODEL = "claude-sonnet-4-20250514";

    private static final String PR_DIFF = String.join("\n",
            "diff --git a/src/main/java/com/example/Calculator.java b/src/main/java/com/example/Calculator.java",
            "index 7f8a2b1..9c3d4e5 100644",
            "--- a/src/main/java/com/example/Calculator.java",
            "+++ b/src/main/java/com/example/Calculator.java",
            "@@ -1,6 +1,7 @@",
            " package com.example;",
            " ",
            " public class Calculator {",
            "+    public int add(int a, int b) { return a + b; }",
            "     public int subtract(int a, int b) { return a - b; }",
            " }");

    private static final String GOOD_TEST = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "import static org.junit.jupiter.api.Assertions.assertEquals;",
            "",
            "public class GoodTest {",
            "",
            "    @Test",
            "    void adds() {",
            "        assertEquals(4, new Calculator().add(2, 2));",
            "    }",
            "}",
            "");

    private static final String BAD_COMPILE_TEST = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "public class BadCompileTest {",
            "",
            "    @Test",
            "    void usesMissingClass() {",
            "        new MissingHelper().doThing();",
            "    }",
            "}",
            "");

    private static final String FAILING_TEST = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "import static org.junit.jupiter.api.Assertions.assertEquals;",
            "",
            "public class FailingTest {",
            "",
            "    @Test",
            "    void fails() {",
            "        assertEquals(5, 2 + 2);",
            "    }",
            "}",
            "");

    private static final String FLAKY_TEST = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "import java.io.File;",
            "",
            "public class FlakyTest {",
            "",
            "    @Test",
            "    void flips() throws Exception {",
            "        File marker = new File(\"/tmp/flaky-marker\");",
            "        if (marker.exists()) {",
            "            throw new AssertionError(\"flaky failure on second run\");",
            "        }",
            "        marker.createNewFile();",
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
        registry.add("copilotguard.validation.junit-console-jar",
                ValidationGateIntegrationTest::junitConsoleJar);
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
    void setUp() {
        WM.resetAll();
        reviewCommentRepository.deleteAll();
        generatedTestRepository.deleteAll();
        reviewRunRepository.deleteAll();
        promptAuditRepository.deleteAll();
    }

    @Test
    void validatesGeneratedTestsAndNeverAcceptsCompileFailures() throws Exception {
        FixtureRepo repo = createRepo();
        stubGithub(repo);
        stubAnthropic();

        ResponseEntity<ReviewResponse> response = restTemplate.postForEntity("/api/v1/reviews",
                new ReviewRequest(null, "acme", "widgets", 7, null, false, null, null), ReviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("SUCCEEDED");
        assertThat(body.generatedTests()).hasSize(4);

        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("GoodTest");
            assertThat(test.validationStatus()).isEqualTo("PASSING");
            assertThat(test.accepted()).isTrue();
            assertThat(test.compileStatus()).isEqualTo("SUCCESS");
            assertThat(test.passStatus()).isEqualTo("PASSED");
            assertThat(test.content()).contains("class GoodTest");
        });
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("BadCompileTest");
            assertThat(test.validationStatus()).isEqualTo("COMPILE_FAIL");
            assertThat(test.accepted()).isFalse();
            assertThat(test.content()).isNull();
            assertThat(test.validationDetail()).contains("MissingHelper");
        });
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("FailingTest");
            assertThat(test.validationStatus()).isEqualTo("TEST_FAIL");
            assertThat(test.accepted()).isFalse();
        });
        assertThat(body.generatedTests()).anySatisfy(test -> {
            assertThat(test.filePath()).contains("FlakyTest");
            assertThat(test.validationStatus()).isEqualTo("FLAKY");
            assertThat(test.accepted()).isFalse();
            assertThat(test.validationDetail()).contains("non-deterministic");
        });

        assertThat(body.generatedTests())
                .filteredOn(test -> test.filePath().contains("BadCompileTest"))
                .allSatisfy(test -> assertThat(test.accepted()).isFalse());
        assertThat(body.generatedTests())
                .filteredOn(ReviewResponse.TestSummary::accepted)
                .extracting(ReviewResponse.TestSummary::filePath)
                .noneMatch(path -> path.contains("BadCompileTest"));

        List<GeneratedTest> persisted = generatedTestRepository.findAll();
        assertThat(persisted).hasSize(4);
        assertThat(persisted)
                .filteredOn(test -> test.getFilePath().contains("GoodTest"))
                .allSatisfy(test -> assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.PASSING));
        assertThat(persisted)
                .filteredOn(test -> test.getFilePath().contains("BadCompileTest"))
                .allSatisfy(test -> assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.COMPILE_FAIL));
        assertThat(persisted)
                .filteredOn(test -> test.getFilePath().contains("FailingTest"))
                .allSatisfy(test -> assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.TEST_FAIL));
        assertThat(persisted)
                .filteredOn(test -> test.getFilePath().contains("FlakyTest"))
                .allSatisfy(test -> assertThat(test.getValidationStatus()).isEqualTo(ValidationStatus.FLAKY));

        long runId = reviewRunRepository.findAll().get(0).getId();
        ResponseEntity<AuditTrailResponse> auditResponse =
                restTemplate.getForEntity("/api/v1/reviews/" + runId + "/audit", AuditTrailResponse.class);
        assertThat(auditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditResponse.getBody()).isNotNull();
        assertThat(auditResponse.getBody().verdicts()).extracting(AuditTrailResponse.VerdictEntry::validationStatus)
                .containsExactlyInAnyOrder("PASSING", "COMPILE_FAIL", "TEST_FAIL", "FLAKY");
    }

    private void stubAnthropic() throws JsonProcessingException {
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_tests")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(testGenResponse())));
        WM.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("submit_review_comments")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(reviewResponse())));
    }

    private void stubGithub(FixtureRepo repo) {
        WM.stubFor(get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"base\":{\"sha\":\"" + repo.sha() + "\"},\"head\":{\"sha\":\"" + repo.sha()
                                + "\",\"repo\":{\"clone_url\":\"file://" + repo.dir() + "\"}}}")));
        WM.stubFor(get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                .withHeader("Accept", equalTo("application/vnd.github.v3.diff"))
                .willReturn(aResponse().withBody(PR_DIFF)));
        WM.stubFor(get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                .willReturn(aResponse().withStatus(404)));
    }

    private String testGenResponse() throws JsonProcessingException {
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
        files.addObject().put("path", "src/test/java/com/example/GoodTest.java").put("content", GOOD_TEST);
        files.addObject().put("path", "src/test/java/com/example/BadCompileTest.java").put("content", BAD_COMPILE_TEST);
        files.addObject().put("path", "src/test/java/com/example/FailingTest.java").put("content", FAILING_TEST);
        files.addObject().put("path", "src/test/java/com/example/FlakyTest.java").put("content", FLAKY_TEST);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 1400);
        usage.put("output_tokens", 900);
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
        ObjectNode comment = comments.addObject();
        comment.put("file", "src/main/java/com/example/Calculator.java");
        comment.put("line", 10);
        comment.put("severity", "MAJOR");
        comment.put("category", "BUG");
        comment.put("body", "Missing null check on divisor");
        comment.put("suggestedFix", "Guard the divisor");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 800);
        usage.put("output_tokens", 250);
        return objectMapper.writeValueAsString(root);
    }

    private static FixtureRepo createRepo() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("copilotguard-gate-repo");
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
        return new FixtureRepo(dir, sha);
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

    private record FixtureRepo(Path dir, String sha) {
    }
}
