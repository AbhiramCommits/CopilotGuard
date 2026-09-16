package com.copilotguard.api;

import com.copilotguard.audit.PromptAudit;
import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewRunRepository reviewRunRepository;
    private final PromptAuditRepository promptAuditRepository;
    private final GeneratedTestRepository generatedTestRepository;

    public ReviewController(ReviewService reviewService,
            ReviewRunRepository reviewRunRepository,
            PromptAuditRepository promptAuditRepository,
            GeneratedTestRepository generatedTestRepository) {
        this.reviewService = reviewService;
        this.reviewRunRepository = reviewRunRepository;
        this.promptAuditRepository = promptAuditRepository;
        this.generatedTestRepository = generatedTestRepository;
    }

    @PostMapping("/reviews")
    public ResponseEntity<ReviewResponse> createReview(@Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request));
    }

    @GetMapping("/reviews/{id}/audit")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(@PathVariable long id) {
        ReviewRun run = reviewRunRepository.findById(id)
                .orElseThrow(() -> new RunNotFoundException(id));
        List<PromptAudit> audits = promptAuditRepository.findByRunId(String.valueOf(id));
        List<GeneratedTest> tests = generatedTestRepository.findByReviewRunId(id);
        AuditTrailResponse response = new AuditTrailResponse(
                run.getId(),
                run.getStatus().name(),
                run.getPromptTemplateId(),
                audits.stream().map(a -> new AuditTrailResponse.AuditEntry(
                        a.getTemplateId(), a.getTemplateVersion(), a.getRedactedPrompt(), a.getRawResponse(),
                        a.getModel(), a.getLatencyMs(), a.getTokensIn(), a.getTokensOut(),
                        a.getRedactionHits(), a.getTimestamp())).toList(),
                tests.stream().map(t -> new AuditTrailResponse.VerdictEntry(
                        t.getFilePath(),
                        t.getValidationStatus().name(),
                        t.getValidationDetail())).toList());
        return ResponseEntity.ok(response);
    }
}
