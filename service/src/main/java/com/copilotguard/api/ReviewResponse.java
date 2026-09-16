package com.copilotguard.api;

import java.math.BigDecimal;
import java.util.List;

public record ReviewResponse(
        long runId,
        String repo,
        String baseSha,
        String headSha,
        String promptTemplateId,
        String status,
        long tokenInput,
        long tokenOutput,
        BigDecimal costUsd,
        List<TestSummary> generatedTests,
        List<CommentSummary> comments) {

    public record TestSummary(String filePath, String compileStatus, String passStatus,
            String validationStatus, boolean accepted, String validationDetail, String content) {
    }

    public record CommentSummary(Long id, String filePath, Integer line, String severity, String category,
            String body) {
    }
}
