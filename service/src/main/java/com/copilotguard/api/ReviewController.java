package com.copilotguard.api;

import com.copilotguard.audit.PromptAudit;
import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.ReviewComment;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.metrics.MetricsService;
import com.copilotguard.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reviews", description = "Diff review, validation gate, and audit trail")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewRunRepository reviewRunRepository;
    private final PromptAuditRepository promptAuditRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final MetricsService metricsService;

    public ReviewController(
            ReviewService reviewService,
            ReviewRunRepository reviewRunRepository,
            PromptAuditRepository promptAuditRepository,
            GeneratedTestRepository generatedTestRepository,
            ReviewCommentRepository reviewCommentRepository,
            MetricsService metricsService) {
        this.reviewService = reviewService;
        this.reviewRunRepository = reviewRunRepository;
        this.promptAuditRepository = promptAuditRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.metricsService = metricsService;
    }

    @PostMapping("/reviews")
    @Operation(
            summary = "Run a review",
            description =
                    "Accepts a raw unified diff or a GitHub PR reference. Redacts secrets, "
                            + "generates tests and review comments with the LLM, validates generated tests in Docker, "
                            + "enforces repository conventions, and persists the audit trail. "
                            + "Only PASSING tests are returned as accepted output.")
    @ApiResponse(responseCode = "201", description = "Review completed (SUCCEEDED or PARTIAL)")
    @ApiResponse(responseCode = "400", description = "Invalid request or unparseable diff")
    @ApiResponse(responseCode = "422", description = "Blocker secret found or cost budget exceeded")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    @ApiResponse(responseCode = "502", description = "Upstream failure (LLM, GitHub, validation)")
    public ResponseEntity<ReviewResponse> createReview(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Review request",
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"diff\": \"diff --git a/A.java b/A.java\\n"
                                                                            + "--- a/A.java\\n+++ b/A.java\\n"
                                                                            + "@@ -1 +1 @@\\n-x\\n+y\", "
                                                                            + "\"allowRedactedSend\": false}")))
                    @Valid
                    @RequestBody
                    ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request));
    }

    @GetMapping("/reviews/{id}/audit")
    @Operation(
            summary = "Read the audit trail for a run",
            description =
                    "Returns the immutable audit trail: redacted prompts, raw responses, "
                            + "template and model ids, redaction hits, and per-test validation verdicts.")
    @ApiResponse(responseCode = "200", description = "Audit trail")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(
            @Parameter(description = "Review run id", example = "42") @PathVariable long id) {
        ReviewRun run =
                reviewRunRepository.findById(id).orElseThrow(() -> new RunNotFoundException(id));
        List<PromptAudit> audits = promptAuditRepository.findByRunId(String.valueOf(id));
        List<GeneratedTest> tests = generatedTestRepository.findByReviewRunId(id);
        AuditTrailResponse response =
                new AuditTrailResponse(
                        run.getId(),
                        run.getStatus().name(),
                        run.getPromptTemplateId(),
                        audits.stream()
                                .map(
                                        a ->
                                                new AuditTrailResponse.AuditEntry(
                                                        a.getTemplateId(),
                                                        a.getTemplateVersion(),
                                                        a.getRedactedPrompt(),
                                                        a.getRawResponse(),
                                                        a.getModel(),
                                                        a.getLatencyMs(),
                                                        a.getTokensIn(),
                                                        a.getTokensOut(),
                                                        a.getRedactionHits(),
                                                        a.getTimestamp(),
                                                        a.getCorrelationId()))
                                .toList(),
                        tests.stream()
                                .map(
                                        t ->
                                                new AuditTrailResponse.VerdictEntry(
                                                        t.getFilePath(),
                                                        t.getValidationStatus().name(),
                                                        t.getValidationDetail()))
                                .toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reviews/{id}/comments/{commentId}/verdict")
    @Operation(
            summary = "Record the human verdict on a comment",
            description = "Accepts ACCEPT or REJECT. Verdicts feed the human-override metrics.")
    @ApiResponse(responseCode = "200", description = "Verdict recorded")
    @ApiResponse(responseCode = "400", description = "Invalid verdict value")
    @ApiResponse(responseCode = "404", description = "Run or comment not found")
    public ResponseEntity<ReviewResponse.CommentSummary> recordVerdict(
            @Parameter(description = "Review run id", example = "42") @PathVariable long id,
            @Parameter(description = "Comment id", example = "21") @PathVariable long commentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Verdict",
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value = "{\"verdict\": \"REJECT\"}")))
                    @RequestBody
                    VerdictRequest request) {
        ReviewComment comment =
                reviewCommentRepository
                        .findById(commentId)
                        .orElseThrow(
                                () ->
                                        new CommentNotFoundException(
                                                "review comment not found: " + commentId));
        if (comment.getReviewRunId() == null || comment.getReviewRunId() != id) {
            throw new CommentNotFoundException("review comment not found: " + commentId);
        }
        HumanVerdict verdict =
                switch (request.verdict() == null
                        ? ""
                        : request.verdict().toUpperCase(Locale.ROOT)) {
                    case "ACCEPT" -> HumanVerdict.ACCEPTED;
                    case "REJECT" -> HumanVerdict.REJECTED;
                    default ->
                            throw new InvalidVerdictException(
                                    "verdict must be ACCEPT or REJECT, got: " + request.verdict());
                };
        comment.setHumanVerdict(verdict);
        ReviewComment saved = reviewCommentRepository.save(comment);
        metricsService.recordVerdict(
                saved.getCategory(), saved.getSeverity(), saved.getHumanVerdict());
        return ResponseEntity.ok(
                new ReviewResponse.CommentSummary(
                        saved.getId(),
                        saved.getFilePath(),
                        saved.getLine(),
                        saved.getSeverity().name(),
                        saved.getCategory().name(),
                        saved.getBody()));
    }
}
