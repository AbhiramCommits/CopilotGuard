package com.copilotguard.validation;

import java.nio.file.Path;

public interface WorkspaceProvider {

    Path prepare(WorkspaceSpec spec);
}
