package com.copilotguard.validation;

public record WorkspaceSpec(String repo, String cloneUrl, String headSha, String baseSha) {
}
