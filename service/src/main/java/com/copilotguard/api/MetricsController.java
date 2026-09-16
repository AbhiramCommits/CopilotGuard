package com.copilotguard.api;

import com.copilotguard.metrics.MetricsService;
import com.copilotguard.metrics.MetricsSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics/summary")
    public MetricsSummary summary() {
        return metricsService.summary();
    }
}
