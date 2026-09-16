package com.copilotguard.metrics;

import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.Severity;
import com.copilotguard.domain.ValidationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PrometheusMetricsService implements MetricsService {

    private final MeterRegistry meterRegistry;
    private final GeneratedTestRepository generatedTestRepository;
    private final ReviewRunRepository reviewRunRepository;
    private final ReviewCommentRepository reviewCommentRepository;

    public PrometheusMetricsService(
            MeterRegistry meterRegistry,
            GeneratedTestRepository generatedTestRepository,
            ReviewRunRepository reviewRunRepository,
            ReviewCommentRepository reviewCommentRepository) {
        this.meterRegistry = meterRegistry;
        this.generatedTestRepository = generatedTestRepository;
        this.reviewRunRepository = reviewRunRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        Gauge.builder("copilotguard.tests.pass_rate", this::generatedTestPassRate)
                .description("Fraction of generated tests that passed validation")
                .register(meterRegistry);
        Gauge.builder("copilotguard.reviews.mean_cost_usd", this::meanTokenCostPerReview)
                .description("Mean token cost in USD per review run")
                .register(meterRegistry);
        Gauge.builder("copilotguard.comments.override_rate", this::humanOverrideRate)
                .description("Fraction of review comments rejected by a human")
                .register(meterRegistry);
    }

    @Override
    public void recordVerdict(CommentCategory category, Severity severity, HumanVerdict verdict) {
        if (verdict == HumanVerdict.REJECTED) {
            meterRegistry
                    .counter(
                            "copilotguard.comments.rejected",
                            "category",
                            category.name(),
                            "severity",
                            severity.name())
                    .increment();
        }
    }

    @Override
    public MetricsSummary summary() {
        return new MetricsSummary(
                generatedTestPassRate(),
                meanTokenCostPerReview(),
                humanOverrideRate(),
                rejectionReasons());
    }

    private double generatedTestPassRate() {
        long total = generatedTestRepository.count();
        if (total == 0) {
            return 0.0;
        }
        return (double) generatedTestRepository.countByValidationStatus(ValidationStatus.PASSING)
                / total;
    }

    private double meanTokenCostPerReview() {
        return reviewRunRepository.averageCostUsd().doubleValue();
    }

    private double humanOverrideRate() {
        long total = reviewCommentRepository.count();
        if (total == 0) {
            return 0.0;
        }
        return (double) reviewCommentRepository.countByHumanVerdict(HumanVerdict.REJECTED) / total;
    }

    private List<MetricsSummary.RejectionReason> rejectionReasons() {
        return reviewCommentRepository
                .countRejectedByCategoryAndSeverity(HumanVerdict.REJECTED)
                .stream()
                .map(
                        row ->
                                new MetricsSummary.RejectionReason(
                                        ((CommentCategory) row[0]).name(),
                                        ((Severity) row[1]).name(),
                                        ((Number) row[2]).longValue()))
                .toList();
    }
}
