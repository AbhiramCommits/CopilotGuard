package com.copilotguard.domain;

public enum ValidationStatus {
    PENDING,
    COMPILE_FAIL,
    TEST_FAIL,
    FLAKY,
    PASSING
}
