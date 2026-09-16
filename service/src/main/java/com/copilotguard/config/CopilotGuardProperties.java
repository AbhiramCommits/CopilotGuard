package com.copilotguard.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copilotguard")
public record CopilotGuardProperties(
        String anthropicApiKey, Anthropic anthropic, Github github, Validation validation) {

    public record Anthropic(
            String baseUrl,
            String model,
            String version,
            int maxTokens,
            int maxAttempts,
            Duration timeout,
            Map<String, ModelPrice> prices) {}

    public record ModelPrice(BigDecimal inputPerMTok, BigDecimal outputPerMTok) {}

    public record Github(String baseUrl, String rawBaseUrl, String token) {}

    public record Validation(
            String image,
            Duration compileTimeout,
            Duration runTimeout,
            long memoryMb,
            double cpus,
            String junitConsoleJar) {}
}
