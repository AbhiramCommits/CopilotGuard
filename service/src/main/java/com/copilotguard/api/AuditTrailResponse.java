package com.copilotguard.api;

import java.time.Instant;
import java.util.List;

public record AuditTrailResponse(
        long runId,
        String status,
        String promptTemplateId,
        List<AuditEntry> audits,
        List<VerdictEntry> verdicts) {

    public record AuditEntry(String templateId, String templateVersion, String redactedPrompt, String rawResponse,
            String model, Long latencyMs, Integer tokensIn, Integer tokensOut, List<String> redactionHits,
            Instant timestamp) {
    }

    public record VerdictEntry(String filePath, String validationStatus, String validationDetail) {
    }
}
