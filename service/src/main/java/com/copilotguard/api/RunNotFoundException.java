package com.copilotguard.api;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(long runId) {
        super("review run not found: " + runId);
    }
}
