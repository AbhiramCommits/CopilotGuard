package com.copilotguard.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateRegistryTest {

    @Test
    void loadsManifestAndRendersTemplates() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry(new DefaultResourceLoader());

        PromptTemplate generate = registry.latest("generate_tests");
        assertThat(generate.id()).isEqualTo("generate_tests");
        assertThat(generate.version()).isEqualTo("v1");
        assertThat(generate.purpose()).isEqualTo(PromptPurpose.TEST_GEN);
        assertThat(generate.changelog()).isNotBlank();
        assertThat(generate.content()).contains("{{diff}}");

        PromptTemplate review = registry.latest("review_diff");
        assertThat(review.purpose()).isEqualTo(PromptPurpose.REVIEW);
        assertThat(registry.get("review_diff", "v1").content()).contains("BLOCKER");
    }

    @Test
    void rendersDiffAndConventionsWithoutHtmlEscaping() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry(new DefaultResourceLoader());
        PromptRenderer renderer = new PromptRenderer();

        String rendered = renderer.render(registry.latest("generate_tests"), Map.of(
                "repo", "acme/widgets",
                "baseSha", "abc123",
                "headSha", "def456",
                "diff", "--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@\n-x\n+y\n",
                "conventions", "bannedApis: []\n"));

        assertThat(rendered).contains("acme/widgets");
        assertThat(rendered).contains("abc123");
        assertThat(rendered).contains("+y");
        assertThat(rendered).doesNotContain("&lt;");
    }
}
