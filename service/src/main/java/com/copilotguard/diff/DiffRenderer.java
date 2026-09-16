package com.copilotguard.diff;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class DiffRenderer {

    public String render(List<FilePatch> patches) {
        StringBuilder sb = new StringBuilder();
        for (FilePatch patch : patches) {
            String path = patch.newPath() != null && !"/dev/null".equals(patch.newPath())
                    ? patch.newPath()
                    : patch.oldPath();
            sb.append("### ").append(path).append('\n');
            for (Hunk hunk : patch.hunks()) {
                sb.append(String.format(Locale.ROOT, "@@ -%d,%d +%d,%d @@%n",
                        hunk.oldStart(), hunk.oldCount(), hunk.newStart(), hunk.newCount()));
                for (HunkLine line : hunk.lines()) {
                    char prefix = switch (line.type()) {
                        case ADD -> '+';
                        case REMOVE -> '-';
                        case CONTEXT -> ' ';
                    };
                    sb.append(prefix).append(line.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
