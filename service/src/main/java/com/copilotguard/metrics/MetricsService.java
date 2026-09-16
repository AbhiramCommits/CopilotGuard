package com.copilotguard.metrics;

import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.Severity;

public interface MetricsService {

    void recordVerdict(CommentCategory category, Severity severity, HumanVerdict verdict);

    MetricsSummary summary();
}
