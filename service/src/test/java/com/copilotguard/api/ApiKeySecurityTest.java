package com.copilotguard.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.copilotguard.audit.PromptAuditRepository;
import com.copilotguard.config.ApiKeyAuthFilter;
import com.copilotguard.config.SecurityConfig;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.metrics.MetricsService;
import com.copilotguard.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ReviewController.class, MetricsController.class})
@Import({SecurityConfig.class, ApiKeyAuthFilter.class})
@TestPropertySource(properties = "copilotguard.api-key=secret-key")
class ApiKeySecurityTest {

    @Autowired MockMvc mockMvc;

    @Autowired ObjectMapper objectMapper;

    @MockBean ReviewService reviewService;

    @MockBean ReviewRunRepository reviewRunRepository;

    @MockBean PromptAuditRepository promptAuditRepository;

    @MockBean GeneratedTestRepository generatedTestRepository;

    @MockBean ReviewCommentRepository reviewCommentRepository;

    @MockBean MetricsService metricsService;

    private static final String VALID_DIFF = "diff --git a/A b/A\n@@ -1 +1 @@\n-x\n+y";

    @Test
    void writeEndpointRequiresApiKey() throws Exception {
        when(reviewService.createReview(any()))
                .thenReturn(
                        new ReviewResponse(
                                1,
                                "local",
                                "a",
                                "b",
                                "generate_tests:v1",
                                "SUCCEEDED",
                                0,
                                0,
                                BigDecimal.ZERO,
                                List.of(),
                                List.of(),
                                List.of()));

        String body = "{\"diff\":" + toJson(VALID_DIFF) + "}";

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .header(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/reviews")
                                .header(ApiKeyAuthFilter.API_KEY_HEADER, "secret-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());
    }

    private String toJson(String value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void readEndpointsStayOpen() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/1/audit")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/metrics/summary")).andExpect(status().isOk());
    }
}
