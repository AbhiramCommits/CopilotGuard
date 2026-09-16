package com.copilotguard.api;

import com.copilotguard.metrics.MetricsService;
import com.copilotguard.metrics.MetricsSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Metrics", description = "Aggregate quality and governance metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics/summary")
    @Operation(
            summary = "Quality metrics summary",
            description =
                    "Generated-test pass rate, mean token cost per review, human-override "
                            + "rate, and the rejection-reasons histogram. The same values are exported "
                            + "as Prometheus metrics at /actuator/prometheus.")
    @ApiResponse(responseCode = "200", description = "Metrics summary")
    public MetricsSummary summary() {
        return metricsService.summary();
    }
}
