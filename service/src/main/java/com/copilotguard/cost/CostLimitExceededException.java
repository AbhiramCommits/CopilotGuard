package com.copilotguard.cost;

public class CostLimitExceededException extends RuntimeException {

    public CostLimitExceededException(String message) {
        super(message);
    }
}
