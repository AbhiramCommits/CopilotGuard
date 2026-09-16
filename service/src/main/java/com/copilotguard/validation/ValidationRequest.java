package com.copilotguard.validation;

import com.copilotguard.llm.GeneratedTestFile;

import java.nio.file.Path;
import java.util.List;

public record ValidationRequest(Path workspaceDir, List<GeneratedTestFile> tests) {
}
