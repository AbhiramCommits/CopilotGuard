package com.copilotguard.conventions;

import java.util.List;

public record CopilotGuardConventions(
        String testClassNamePattern,
        List<String> bannedApis,
        List<String> requiredTestAnnotations,
        int maxMethodLength) {}
