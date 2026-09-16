package com.copilotguard.llm;

import java.util.List;

public record TestGenerationResult(
        List<GeneratedTestFile> files,
        LlmUsage usage,
        String rawRequest,
        String rawResponse,
        long latencyMs,
        String model) {
}
