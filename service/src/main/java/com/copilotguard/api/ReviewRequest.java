package com.copilotguard.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.util.StringUtils;

public record ReviewRequest(
        String diff,
        String owner,
        String repo,
        Integer prNumber,
        String conventions,
        boolean allowRedactedSend,
        String testPromptTemplateId,
        String reviewPromptTemplateId) {

    @AssertTrue(message = "provide either 'diff' or {owner, repo, prNumber}")
    @JsonIgnore
    public boolean isSourceValid() {
        boolean hasDiff = StringUtils.hasText(diff);
        boolean hasPr = StringUtils.hasText(owner) && StringUtils.hasText(repo) && prNumber != null;
        return hasDiff ^ hasPr;
    }
}
