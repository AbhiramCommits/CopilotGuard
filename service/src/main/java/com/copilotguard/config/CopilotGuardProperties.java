package com.copilotguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "copilotguard")
public record CopilotGuardProperties(
        String anthropicApiKey,
        Anthropic anthropic,
        Github github) {

    public record Anthropic(
            String baseUrl,
            String model,
            String version,
            int maxTokens,
            int maxAttempts,
            Duration timeout,
            Map<String, ModelPrice> prices) {
    }

    public record ModelPrice(BigDecimal inputPerMTok, BigDecimal outputPerMTok) {
    }

    public record Github(String baseUrl, String rawBaseUrl, String token) {
    }
}
