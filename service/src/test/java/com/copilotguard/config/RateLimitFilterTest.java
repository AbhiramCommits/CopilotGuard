package com.copilotguard.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(2, 1, Duration.ofMinutes(1));
    }

    @Test
    void allowsRequestsWithinCapacity() throws ServletException, IOException {
        MockHttpServletRequest first = reviewRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();

        filter.doFilter(first, firstResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestsBeyondCapacityWith429() throws ServletException, IOException {
        MockHttpServletRequest request = reviewRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());
        filter.doFilter(request, response, new MockFilterChain());
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("rate limit exceeded");
    }

    @Test
    void bucketsByApiKeySeparately() throws ServletException, IOException {
        MockHttpServletRequest keyA = reviewRequest();
        keyA.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "key-a");
        MockHttpServletRequest keyB = reviewRequest();
        keyB.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "key-b");
        MockHttpServletResponse responseA = new MockHttpServletResponse();
        MockHttpServletResponse responseB = new MockHttpServletResponse();

        filter.doFilter(keyA, responseA, new MockFilterChain());
        filter.doFilter(keyB, responseB, new MockFilterChain());
        filter.doFilter(keyA, responseA, new MockFilterChain());
        filter.doFilter(keyA, responseA, new MockFilterChain());

        assertThat(responseA.getStatus()).isEqualTo(429);
        assertThat(responseB.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonReviewEndpoints() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest reviewRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
