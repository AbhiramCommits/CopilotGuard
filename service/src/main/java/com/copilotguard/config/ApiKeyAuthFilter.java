package com.copilotguard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyAuthFilter(@Value("${copilotguard.api-key:}") String apiKey) {
        this.expectedKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expectedKey.length == 0 || !isWriteEndpoint(request);
    }

    private static boolean isWriteEndpoint(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/reviews");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader(API_KEY_HEADER);
        if (StringUtils.hasText(provided)
                && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        String.format(
                                Locale.ROOT,
                                "{\"status\":401,\"message\":\"missing or invalid %s header\"}",
                                API_KEY_HEADER));
    }
}
