package com.copilotguard.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.util.StringUtils;

@Schema(
        description =
                "Request for a review run: either a raw unified diff or a GitHub PR reference")
public record ReviewRequest(
        @Schema(
                        description = "Raw unified diff text",
                        example = "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-x\n+y")
                String diff,
        @Schema(description = "Repository owner for the GitHub path", example = "acme")
                String owner,
        @Schema(description = "Repository name for the GitHub path", example = "widgets")
                String repo,
        @Schema(description = "Pull request number for the GitHub path", example = "7")
                Integer prNumber,
        @Schema(
                        description = "Inline copilotguard.yml conventions (YAML)",
                        example = "bannedApis:\n  - \"System\\.exit\"\n")
                String conventions,
        @Schema(
                        description =
                                "Send redacted content even when blocker-class secrets are found",
                        example = "false")
                boolean allowRedactedSend,
        @Schema(description = "Test-generation prompt template id", example = "generate_tests")
                String testPromptTemplateId,
        @Schema(description = "Review prompt template id", example = "review_diff_cot")
                String reviewPromptTemplateId) {

    @AssertTrue(message = "provide either 'diff' or {owner, repo, prNumber}")
    @JsonIgnore
    public boolean isSourceValid() {
        boolean hasDiff = StringUtils.hasText(diff);
        boolean hasPr = StringUtils.hasText(owner) && StringUtils.hasText(repo) && prNumber != null;
        return hasDiff ^ hasPr;
    }
}
