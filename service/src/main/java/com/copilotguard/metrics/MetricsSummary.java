package com.copilotguard.metrics;

import java.util.List;

public record MetricsSummary(
        double generatedTestPassRate,
        double meanTokenCostPerReview,
        double humanOverrideRate,
        List<RejectionReason> rejectionReasons) {

    public record RejectionReason(String category, String severity, long count) {
    }
}
