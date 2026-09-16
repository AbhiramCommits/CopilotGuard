package com.copilotguard.diff;

public record HunkLine(LineType type, Integer oldLine, Integer newLine, String content) {

    public enum LineType {
        CONTEXT,
        ADD,
        REMOVE
    }
}
