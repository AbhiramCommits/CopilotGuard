package com.copilotguard.github;

import com.copilotguard.config.CopilotGuardProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GitHubClient {

    private static final int TIMEOUT_MILLIS = 30_000;

    private final RestClient apiClient;
    private final RestClient rawClient;
    private final ObjectMapper objectMapper;

    public GitHubClient(CopilotGuardProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(TIMEOUT_MILLIS);
        RestClient.Builder api =
                RestClient.builder()
                        .baseUrl(properties.github().baseUrl())
                        .requestFactory(requestFactory)
                        .defaultHeader(HttpHeaders.USER_AGENT, "CopilotGuard/0.1");
        RestClient.Builder raw =
                RestClient.builder()
                        .baseUrl(properties.github().rawBaseUrl())
                        .requestFactory(requestFactory)
                        .defaultHeader(HttpHeaders.USER_AGENT, "CopilotGuard/0.1");
        if (StringUtils.hasText(properties.github().token())) {
            api =
                    api.defaultHeader(
                            HttpHeaders.AUTHORIZATION, "Bearer " + properties.github().token());
            raw =
                    raw.defaultHeader(
                            HttpHeaders.AUTHORIZATION, "Bearer " + properties.github().token());
        }
        this.apiClient = api.build();
        this.rawClient = raw.build();
    }

    public PrInfo getPrInfo(String owner, String repo, int prNumber) {
        String body =
                apiClient
                        .get()
                        .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                        .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .retrieve()
                        .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body);
            String baseSha = root.path("base").path("sha").asText();
            String headSha = root.path("head").path("sha").asText();
            String cloneUrl = root.path("head").path("repo").path("clone_url").asText("");
            if (cloneUrl.isBlank()) {
                cloneUrl = root.path("base").path("repo").path("clone_url").asText("");
            }
            if (baseSha.isBlank() || headSha.isBlank()) {
                throw new GitHubException("PR metadata missing base/head sha");
            }
            return new PrInfo(baseSha, headSha, cloneUrl);
        } catch (JsonProcessingException ex) {
            throw new GitHubException("failed to parse PR metadata", ex);
        }
    }

    public String getPrDiff(String owner, String repo, int prNumber) {
        return apiClient
                .get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, prNumber)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    public Optional<String> fetchConventionsYaml(String owner, String repo) {
        try {
            return Optional.of(
                    rawClient
                            .get()
                            .uri("/{owner}/{repo}/HEAD/.github/copilotguard.yml", owner, repo)
                            .retrieve()
                            .body(String.class));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new GitHubException(
                    "failed to fetch copilotguard.yml: " + ex.getStatusCode(), ex);
        }
    }

    public record PrInfo(String baseSha, String headSha, String cloneUrl) {}
}
