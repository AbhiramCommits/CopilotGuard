package com.copilotguard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(SecurityProperties.BASIC_AUTH_ORDER - 10)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${copilotguard.api-key:}") String apiKey,
            ApiKeyAuthFilter apiKeyAuthFilter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        if (StringUtils.hasText(apiKey)) {
            // API-key auth on write endpoints is enforced by ApiKeyAuthFilter,
            // which rejects requests without a valid X-API-Key header.
            http.addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
