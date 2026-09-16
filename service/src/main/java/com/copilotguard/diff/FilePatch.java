package com.copilotguard.diff;

import java.util.List;

public record FilePatch(String oldPath, String newPath, List<Hunk> hunks) {}
