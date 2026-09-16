package com.copilotguard.validation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SyntheticWorkspaceProvider implements WorkspaceProvider {

    @Override
    public Path prepare(WorkspaceSpec spec) {
        try {
            return Files.createTempDirectory("copilotguard-workspace");
        } catch (IOException ex) {
            throw new ValidationException("failed to create workspace directory", ex);
        }
    }
}
