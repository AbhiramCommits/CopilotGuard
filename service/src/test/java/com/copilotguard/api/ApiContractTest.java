package com.copilotguard.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.copilotguard.audit.BlockedSecretException;
import com.copilotguard.audit.PromptAudit;
import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.config.ApiKeyAuthFilter;
import com.copilotguard.config.SecurityConfig;
import com.copilotguard.diff.DiffParseException;
import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.ReviewComment;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.ReviewRunStatus;
import com.copilotguard.domain.Severity;
import com.copilotguard.domain.ValidationStatus;
import com.copilotguard.llm.LlmException;
import com.copilotguard.metrics.MetricsService;
import com.copilotguard.metrics.MetricsSummary;
import com.copilotguard.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {HealthController.class, ReviewController.class, MetricsController.class})
@Import({SecurityConfig.class, ApiKeyAuthFilter.class})
class ApiContractTest {

    @Autowired MockMvc mockMvc;

    @Autowired ObjectMapper objectMapper;

    @MockBean HealthEndpoint healthEndpoint;

    @MockBean ReviewService reviewService;

    @MockBean ReviewRunRepository reviewRunRepository;

    @MockBean PromptAuditRepository promptAuditRepository;

    @MockBean GeneratedTestRepository generatedTestRepository;

    @MockBean ReviewCommentRepository reviewCommentRepository;

    @MockBean MetricsService metricsService;

    @Test
    void healthReturnsUp() throws Exception {
        Health health = Health.status(Status.UP).build();
        when(healthEndpoint.health()).thenReturn(health);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthReturnsServiceUnavailableWhenDown() throws Exception {
        Health health = Health.status(Status.DOWN).build();
        when(healthEndpoint.health()).thenReturn(health);

        mockMvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void createReviewReturnsCreatedWithBody() throws Exception {
        ReviewResponse response =
                new ReviewResponse(
                        1,
                        "local",
                        "a",
                        "b",
                        "generate_tests:v1",
                        "SUCCEEDED",
                        2100,
                        1100,
                        new BigDecimal("0.0228"),
                        List.of(
                                new ReviewResponse.TestSummary(
                                        "src/test/java/FooTest.java",
                                        "SUCCESS",
                                        "PASSED",
                                        "PASSING",
                                        true,
                                        "passed",
                                        "class FooTest {}")),
                        List.of(
                                new ReviewResponse.CommentSummary(
                                        1L, "Foo.java", 3, "BLOCKER", "BUG", "bad")),
                        List.of());
        when(reviewService.createReview(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "diff",
                                                        "diff --git a/A b/A\n@@ -1 +1 @@\n-x\n+y",
                                                        "allowRedactedSend",
                                                        true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(1))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.generatedTests[0].accepted").value(true))
                .andExpect(jsonPath("$.comments[0].severity").value("BLOCKER"));
    }

    @Test
    void createReviewRejectsMissingSource() throws Exception {
        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("provide either 'diff' or")));
    }

    @Test
    void createReviewBlocksSecretsWith422() throws Exception {
        when(reviewService.createReview(any()))
                .thenThrow(
                        new BlockedSecretException("diff contains BLOCKER-class secrets: aws_key"));

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "diff",
                                                        "diff --git a/A b/A\n@@ -1 +1 @@\n-x\n+y"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("aws_key")));
    }

    @Test
    void createReviewMapsLlmFailureTo502() throws Exception {
        when(reviewService.createReview(any())).thenThrow(new LlmException("model down"));

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "diff",
                                                        "diff --git a/A b/A\n@@ -1 +1 @@\n-x\n+y"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(containsString("model down")));
    }

    @Test
    void createReviewMapsBadDiffTo400() throws Exception {
        when(reviewService.createReview(any()))
                .thenThrow(new DiffParseException("no file hunks found in diff"));

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("diff", "not a diff"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("no file hunks")));
    }

    @Test
    void auditTrailReturnsEntriesAndVerdicts() throws Exception {
        ReviewRun run = new ReviewRun();
        run.setId(42L);
        run.setStatus(ReviewRunStatus.SUCCEEDED);
        run.setPromptTemplateId("generate_tests:v1");
        when(reviewRunRepository.findById(42L)).thenReturn(Optional.of(run));

        PromptAudit audit =
                PromptAudit.builder()
                        .runId("42")
                        .templateId("generate_tests")
                        .templateVersion("v1")
                        .redactedPrompt("redacted")
                        .rawResponse("raw")
                        .model("claude-test")
                        .latencyMs(15L)
                        .tokensIn(10)
                        .tokensOut(5)
                        .redactionHits(List.of("email"))
                        .timestamp(Instant.parse("2026-09-16T00:00:00Z"))
                        .correlationId("corr-1")
                        .build();
        when(promptAuditRepository.findByRunId("42")).thenReturn(List.of(audit));

        GeneratedTest test = new GeneratedTest();
        test.setFilePath("src/test/java/FooTest.java");
        test.setValidationStatus(ValidationStatus.PASSING);
        test.setValidationDetail("passed");
        when(generatedTestRepository.findByReviewRunId(42L)).thenReturn(List.of(test));

        mockMvc.perform(get("/api/v1/reviews/42/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(42))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.audits[0].templateId").value("generate_tests"))
                .andExpect(jsonPath("$.audits[0].redactionHits[0]").value("email"))
                .andExpect(jsonPath("$.audits[0].correlationId").value("corr-1"))
                .andExpect(jsonPath("$.verdicts[0].validationStatus").value("PASSING"));
    }

    @Test
    void auditTrailReturns404ForUnknownRun() throws Exception {
        when(reviewRunRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/reviews/42/audit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("review run not found")));
    }

    @Test
    void verdictRecordsReject() throws Exception {
        ReviewComment comment = new ReviewComment();
        comment.setId(7L);
        comment.setReviewRunId(42L);
        comment.setFilePath("Foo.java");
        comment.setLine(3);
        comment.setSeverity(Severity.BLOCKER);
        comment.setCategory(CommentCategory.BUG);
        comment.setBody("bad");
        when(reviewCommentRepository.findById(7L)).thenReturn(Optional.of(comment));
        when(reviewCommentRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(
                        post("/api/v1/reviews/42/comments/7/verdict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"REJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("BLOCKER"))
                .andExpect(jsonPath("$.category").value("BUG"));

        verify(metricsService)
                .recordVerdict(CommentCategory.BUG, Severity.BLOCKER, HumanVerdict.REJECTED);
    }

    @Test
    void verdictAccepts() throws Exception {
        ReviewComment comment = new ReviewComment();
        comment.setId(7L);
        comment.setReviewRunId(42L);
        comment.setFilePath("Foo.java");
        comment.setSeverity(Severity.MINOR);
        comment.setCategory(CommentCategory.STYLE);
        comment.setBody("style");
        when(reviewCommentRepository.findById(7L)).thenReturn(Optional.of(comment));
        when(reviewCommentRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(
                        post("/api/v1/reviews/42/comments/7/verdict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"ACCEPT\"}"))
                .andExpect(status().isOk());

        verify(metricsService)
                .recordVerdict(CommentCategory.STYLE, Severity.MINOR, HumanVerdict.ACCEPTED);
    }

    @Test
    void verdictRejectsInvalidValue() throws Exception {
        ReviewComment comment = new ReviewComment();
        comment.setId(7L);
        comment.setReviewRunId(42L);
        comment.setFilePath("Foo.java");
        comment.setSeverity(Severity.MAJOR);
        comment.setCategory(CommentCategory.BUG);
        comment.setBody("bad");
        when(reviewCommentRepository.findById(7L)).thenReturn(Optional.of(comment));

        mockMvc.perform(
                        post("/api/v1/reviews/42/comments/7/verdict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ACCEPT or REJECT")));
    }

    @Test
    void verdictReturns404ForUnknownComment() throws Exception {
        when(reviewCommentRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(
                        post("/api/v1/reviews/42/comments/999/verdict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"REJECT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void verdictReturns404WhenCommentBelongsToAnotherRun() throws Exception {
        ReviewComment comment = new ReviewComment();
        comment.setId(7L);
        comment.setReviewRunId(99L);
        comment.setFilePath("Foo.java");
        comment.setSeverity(Severity.MAJOR);
        comment.setCategory(CommentCategory.BUG);
        comment.setBody("bad");
        when(reviewCommentRepository.findById(7L)).thenReturn(Optional.of(comment));

        mockMvc.perform(
                        post("/api/v1/reviews/42/comments/7/verdict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"verdict\":\"REJECT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metricsSummaryReturnsBody() throws Exception {
        when(metricsService.summary())
                .thenReturn(
                        new MetricsSummary(
                                0.5,
                                0.0228,
                                0.1,
                                List.of(new MetricsSummary.RejectionReason("BUG", "BLOCKER", 2L))));

        mockMvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedTestPassRate").value(0.5))
                .andExpect(jsonPath("$.meanTokenCostPerReview").value(0.0228))
                .andExpect(jsonPath("$.humanOverrideRate").value(0.1))
                .andExpect(jsonPath("$.rejectionReasons[0].category").value("BUG"))
                .andExpect(jsonPath("$.rejectionReasons[0].count").value(2));
    }
}
