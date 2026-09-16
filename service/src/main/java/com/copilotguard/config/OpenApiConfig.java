package com.copilotguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI copilotGuardOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("CopilotGuard API")
                                .version("v1")
                                .description(
                                        "Diff review and test generation with a hard validation gate. "
                                                + "Write endpoints require the X-API-Key header when "
                                                + "COPILOTGUARD_API_KEY is configured."));
    }
}
