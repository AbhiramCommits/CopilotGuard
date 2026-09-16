package com.copilotguard.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.Severity;
import com.copilotguard.domain.ValidationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrometheusMetricsServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GeneratedTestRepository generatedTestRepository =
            mock(GeneratedTestRepository.class);
    private final ReviewRunRepository reviewRunRepository = mock(ReviewRunRepository.class);
    private final ReviewCommentRepository reviewCommentRepository =
            mock(ReviewCommentRepository.class);
    private final PrometheusMetricsService metricsService =
            new PrometheusMetricsService(
                    meterRegistry,
                    generatedTestRepository,
                    reviewRunRepository,
                    reviewCommentRepository);

    @Test
    void summaryComputesPassRateCostAndOverrideRate() {
        when(generatedTestRepository.count()).thenReturn(4L);
        when(generatedTestRepository.countByValidationStatus(ValidationStatus.PASSING))
                .thenReturn(2L);
        when(reviewRunRepository.averageCostUsd()).thenReturn(new BigDecimal("0.0228"));
        when(reviewCommentRepository.count()).thenReturn(6L);
        when(reviewCommentRepository.countByHumanVerdict(HumanVerdict.REJECTED)).thenReturn(2L);
        when(reviewCommentRepository.countRejectedByCategoryAndSeverity(HumanVerdict.REJECTED))
                .thenReturn(
                        List.<Object[]>of(
                                new Object[] {CommentCategory.BUG, Severity.BLOCKER, 2L}));

        MetricsSummary summary = metricsService.summary();

        assertThat(summary.generatedTestPassRate()).isEqualTo(0.5);
        assertThat(summary.meanTokenCostPerReview())
                .isCloseTo(0.0228, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(summary.humanOverrideRate()).isEqualTo(1.0 / 3.0);
        assertThat(summary.rejectionReasons()).hasSize(1);
        assertThat(summary.rejectionReasons().get(0).category()).isEqualTo("BUG");
        assertThat(summary.rejectionReasons().get(0).severity()).isEqualTo("BLOCKER");
        assertThat(summary.rejectionReasons().get(0).count()).isEqualTo(2);
    }

    @Test
    void summaryHandlesEmptyDatabase() {
        when(generatedTestRepository.count()).thenReturn(0L);
        when(reviewRunRepository.averageCostUsd()).thenReturn(BigDecimal.ZERO);
        when(reviewCommentRepository.count()).thenReturn(0L);
        when(reviewCommentRepository.countRejectedByCategoryAndSeverity(HumanVerdict.REJECTED))
                .thenReturn(List.of());

        MetricsSummary summary = metricsService.summary();

        assertThat(summary.generatedTestPassRate()).isZero();
        assertThat(summary.humanOverrideRate()).isZero();
        assertThat(summary.rejectionReasons()).isEmpty();
    }

    @Test
    void recordVerdictIncrementsCounterOnlyForRejects() {
        metricsService.recordVerdict(CommentCategory.BUG, Severity.BLOCKER, HumanVerdict.REJECTED);
        metricsService.recordVerdict(CommentCategory.STYLE, Severity.MINOR, HumanVerdict.ACCEPTED);

        assertThat(
                        meterRegistry
                                .counter(
                                        "copilotguard.comments.rejected",
                                        "category",
                                        "BUG",
                                        "severity",
                                        "BLOCKER")
                                .count())
                .isEqualTo(1);
        assertThat(meterRegistry.find("copilotguard.comments.rejected").meters()).hasSize(1);
    }

    @Test
    void registersPrometheusGauges() {
        assertThat(meterRegistry.find("copilotguard.tests.pass_rate").gauge()).isNotNull();
        assertThat(meterRegistry.find("copilotguard.reviews.mean_cost_usd").gauge()).isNotNull();
        assertThat(meterRegistry.find("copilotguard.comments.override_rate").gauge()).isNotNull();
    }
}
