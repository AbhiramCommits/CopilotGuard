package com.copilotguard.validation;

import com.copilotguard.domain.ValidationStatus;
import com.copilotguard.llm.GeneratedTestFile;

public record TestValidationResult(
        GeneratedTestFile test, ValidationStatus status, String detail) {}
