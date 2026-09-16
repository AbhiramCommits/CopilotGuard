package com.copilotguard.llm;

import java.util.List;

public record ReviewGenerationResult(
        List<ReviewCommentSuggestion> comments,
        LlmUsage usage,
        String rawRequest,
        String rawResponse,
        long latencyMs,
        String model) {}
