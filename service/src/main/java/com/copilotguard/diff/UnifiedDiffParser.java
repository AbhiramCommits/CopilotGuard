package com.copilotguard.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    public List<FilePatch> parse(String diffText) {
        List<FilePatch> patches = new ArrayList<>();
        FilePatchBuilder current = null;
        HunkBuilder hunk = null;
        int oldLine = 0;
        int newLine = 0;

        for (String raw : diffText.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    finish(patches, current);
                }
                current = new FilePatchBuilder();
                parseGitHeader(line, current);
                hunk = null;
            } else if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                if (current == null) {
                    current = new FilePatchBuilder();
                }
                String path = parsePath(line.substring(4));
                if (line.startsWith("--- ")) {
                    current.oldPath = path;
                } else {
                    current.newPath = path;
                }
                hunk = null;
            } else if (line.startsWith("@@")) {
                if (current == null) {
                    current = new FilePatchBuilder();
                }
                Matcher matcher = HUNK_HEADER.matcher(line);
                if (!matcher.find()) {
                    throw new DiffParseException("malformed hunk header: " + line);
                }
                hunk =
                        new HunkBuilder(
                                Integer.parseInt(matcher.group(1)),
                                parseIntOrDefault(matcher.group(2), 1),
                                Integer.parseInt(matcher.group(3)),
                                parseIntOrDefault(matcher.group(4), 1));
                current.hunks.add(hunk);
                oldLine = hunk.oldStart;
                newLine = hunk.newStart;
            } else if (hunk == null) {
                continue;
            } else if (line.startsWith("\\")) {
                continue;
            } else if (line.startsWith("+")) {
                hunk.lines.add(
                        new HunkLine(HunkLine.LineType.ADD, null, newLine, line.substring(1)));
                newLine++;
            } else if (line.startsWith("-")) {
                hunk.lines.add(
                        new HunkLine(HunkLine.LineType.REMOVE, oldLine, null, line.substring(1)));
                oldLine++;
            } else if (line.isEmpty() || line.startsWith(" ")) {
                hunk.lines.add(
                        new HunkLine(
                                HunkLine.LineType.CONTEXT,
                                oldLine,
                                newLine,
                                line.isEmpty() ? "" : line.substring(1)));
                oldLine++;
                newLine++;
            }
        }
        if (current != null) {
            finish(patches, current);
        }
        if (patches.isEmpty()) {
            throw new DiffParseException("no file hunks found in diff");
        }
        return patches;
    }

    private static void finish(List<FilePatch> patches, FilePatchBuilder builder) {
        List<Hunk> hunks =
                builder.hunks.stream()
                        .map(
                                h ->
                                        new Hunk(
                                                h.oldStart,
                                                h.oldCount,
                                                h.newStart,
                                                h.newCount,
                                                List.copyOf(h.lines)))
                        .toList();
        if (!hunks.isEmpty()) {
            patches.add(new FilePatch(builder.oldPath, builder.newPath, hunks));
        }
    }

    private static void parseGitHeader(String line, FilePatchBuilder builder) {
        int aIdx = line.indexOf(" a/");
        int bIdx = aIdx >= 0 ? line.indexOf(" b/", aIdx + 2) : -1;
        if (aIdx >= 0 && bIdx > aIdx) {
            builder.oldPath = unquote(line.substring(aIdx + 3, bIdx).trim());
            builder.newPath = unquote(line.substring(bIdx + 3).trim());
        }
    }

    private static String parsePath(String value) {
        String path = value;
        int tab = path.indexOf('\t');
        if (tab >= 0) {
            path = path.substring(0, tab);
        }
        path = path.trim();
        if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        return path;
    }

    private static String unquote(String value) {
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static final class FilePatchBuilder {
        private String oldPath;
        private String newPath;
        private final List<HunkBuilder> hunks = new ArrayList<>();
    }

    private static final class HunkBuilder {
        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final List<HunkLine> lines = new ArrayList<>();

        private HunkBuilder(int oldStart, int oldCount, int newStart, int newCount) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
        }
    }
}
