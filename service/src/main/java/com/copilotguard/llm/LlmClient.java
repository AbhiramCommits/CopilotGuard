package com.copilotguard.llm;

public interface LlmClient {

    TestGenerationResult generateTests(String prompt);

    ReviewGenerationResult reviewDiff(String prompt);
}
