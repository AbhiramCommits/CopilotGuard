package com.copilotguard.diff;

import java.util.List;

public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<HunkLine> lines) {
}
