package com.copilotguard.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int capacity;
    private final int refillTokens;
    private final Duration refillPeriod;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${copilotguard.rate-limit.capacity:120}") int capacity,
            @Value("${copilotguard.rate-limit.refill-tokens:60}") int refillTokens,
            @Value("${copilotguard.rate-limit.refill-period:60s}") Duration refillPeriod) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/reviews"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER);
        if (!StringUtils.hasText(key)) {
            key = request.getRemoteAddr();
        }
        Bucket bucket =
                buckets.computeIfAbsent(
                        key,
                        ignored ->
                                Bucket.builder()
                                        .addLimit(
                                                Bandwidth.classic(
                                                        capacity,
                                                        Refill.greedy(refillTokens, refillPeriod)))
                                        .build());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        String.format(
                                Locale.ROOT,
                                "{\"status\":429,\"message\":\"rate limit exceeded, retry later\"}"));
    }
}
