package com.copilotguard.api;

public class InvalidVerdictException extends RuntimeException {

    public InvalidVerdictException(String message) {
        super(message);
    }
}
