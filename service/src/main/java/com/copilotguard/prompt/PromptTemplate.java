package com.copilotguard.prompt;

public record PromptTemplate(String id, String version, PromptPurpose purpose, String changelog, String content) {
}
