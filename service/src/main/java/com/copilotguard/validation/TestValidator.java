package com.copilotguard.validation;

import java.util.List;

public interface TestValidator {

    List<TestValidationResult> validate(ValidationRequest request);
}
