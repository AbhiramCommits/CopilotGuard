package com.copilotguard.conventions;

import com.copilotguard.domain.Severity;

public record ConventionViolation(String filePath, Integer line, Severity severity, String message) {
}
