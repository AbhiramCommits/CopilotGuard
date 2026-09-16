package com.copilotguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copilotguard")
public record CopilotGuardProperties(
        String anthropicApiKey
) {
}
