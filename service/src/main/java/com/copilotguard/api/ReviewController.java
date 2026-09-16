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
    public ResponseEntity<ReviewResponse> createReview(@Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request));
    }

    @GetMapping("/reviews/{id}/audit")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(@PathVariable long id) {
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
                                                        a.getTimestamp()))
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
    public ResponseEntity<ReviewResponse.CommentSummary> recordVerdict(
            @PathVariable long id,
            @PathVariable long commentId,
            @RequestBody VerdictRequest request) {
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
