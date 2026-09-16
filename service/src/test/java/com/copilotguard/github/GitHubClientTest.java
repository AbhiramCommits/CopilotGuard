package com.copilotguard.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.copilotguard.config.CopilotGuardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class GitHubClientTest {

    @RegisterExtension
    static final WireMockExtension WM =
            WireMockExtension.newInstance()
                    .options(WireMockConfiguration.wireMockConfig().dynamicPort())
                    .build();

    private GitHubClient client(String token) {
        return new GitHubClient(
                new CopilotGuardProperties(
                        null,
                        null,
                        new CopilotGuardProperties.Github(WM.baseUrl(), WM.baseUrl(), token),
                        null,
                        null),
                new ObjectMapper());
    }

    @Test
    void getPrInfoParsesShasAndCloneUrlWithAuth() {
        WM.stubFor(
                get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                        .withHeader("Accept", equalTo("application/vnd.github+json"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"base\":{\"sha\":\"base-sha\"},\"head\":{\"sha\":\"head-sha\","
                                                        + "\"repo\":{\"clone_url\":\"https://example.com/widgets.git\"}}}")));

        GitHubClient.PrInfo info = client("ghp-token").getPrInfo("acme", "widgets", 7);

        assertThat(info.baseSha()).isEqualTo("base-sha");
        assertThat(info.headSha()).isEqualTo("head-sha");
        assertThat(info.cloneUrl()).isEqualTo("https://example.com/widgets.git");

        WM.verify(
                getRequestedFor(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                        .withHeader("Authorization", equalTo("Bearer ghp-token")));
    }

    @Test
    void getPrInfoFallsBackToBaseRepoCloneUrl() {
        WM.stubFor(
                get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"base\":{\"sha\":\"base-sha\","
                                                        + "\"repo\":{\"clone_url\":\"https://example.com/widgets.git\"}},"
                                                        + "\"head\":{\"sha\":\"head-sha\",\"repo\":{}}}")));

        assertThat(client("").getPrInfo("acme", "widgets", 7).cloneUrl())
                .isEqualTo("https://example.com/widgets.git");
    }

    @Test
    void getPrInfoThrowsWhenShaMissing() {
        WM.stubFor(
                get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"base\":{},\"head\":{}}")));

        assertThatThrownBy(() -> client("").getPrInfo("acme", "widgets", 7))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("missing base/head sha");
    }

    @Test
    void getPrDiffUsesDiffMediaType() {
        WM.stubFor(
                get(urlPathEqualTo("/repos/acme/widgets/pulls/7"))
                        .withHeader("Accept", equalTo("application/vnd.github.v3.diff"))
                        .willReturn(aResponse().withBody("diff --git a/A b/A")));

        assertThat(client("").getPrDiff("acme", "widgets", 7)).isEqualTo("diff --git a/A b/A");
    }

    @Test
    void fetchConventionsYamlReturnsText() {
        WM.stubFor(
                get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                        .willReturn(
                                aResponse().withBody("bannedApis:\n  - \"System\\\\.exit\"\n")));

        assertThat(client("").fetchConventionsYaml("acme", "widgets"))
                .hasValueSatisfying(value -> assertThat(value).contains("bannedApis:"));
    }

    @Test
    void fetchConventionsYamlReturnsEmptyOn404() {
        WM.stubFor(
                get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                        .willReturn(aResponse().withStatus(404)));

        assertThat(client("").fetchConventionsYaml("acme", "widgets")).isEmpty();
    }

    @Test
    void fetchConventionsYamlThrowsOnServerError() {
        WM.stubFor(
                get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                        .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client("").fetchConventionsYaml("acme", "widgets"))
                .isInstanceOf(GitHubException.class);
    }

    @Test
    void omitsAuthHeaderWithoutToken() {
        WM.stubFor(
                get(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                        .willReturn(aResponse().withStatus(404)));

        client("").fetchConventionsYaml("acme", "widgets");

        WM.verify(
                getRequestedFor(urlPathEqualTo("/acme/widgets/HEAD/.github/copilotguard.yml"))
                        .withoutHeader("Authorization"));
    }
}
