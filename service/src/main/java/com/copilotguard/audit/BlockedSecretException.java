package com.copilotguard.audit;

public class BlockedSecretException extends RuntimeException {

    public BlockedSecretException(String message) {
        super(message);
    }
}
