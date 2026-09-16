package com.copilotguard.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.copilotguard.diff.HunkLine.LineType.ADD;
import static com.copilotguard.diff.HunkLine.LineType.CONTEXT;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffParserTest {

    private final UnifiedDiffParser parser = new UnifiedDiffParser();

    @Test
    void parsesHunksWithLineNumbers() {
        String diff = String.join("\n",
                "diff --git a/src/main/java/com/example/Calculator.java b/src/main/java/com/example/Calculator.java",
                "index 7f8a2b1..9c3d4e5 100644",
                "--- a/src/main/java/com/example/Calculator.java",
                "+++ b/src/main/java/com/example/Calculator.java",
                "@@ -1,6 +1,7 @@",
                " package com.example;",
                " ",
                " public class Calculator {",
                "+    public int add(int a, int b) { return a + b; }",
                "     public int subtract(int a, int b) { return a - b; }",
                " }");

        List<FilePatch> patches = parser.parse(diff);

        assertThat(patches).hasSize(1);
        FilePatch patch = patches.get(0);
        assertThat(patch.oldPath()).isEqualTo("src/main/java/com/example/Calculator.java");
        assertThat(patch.newPath()).isEqualTo("src/main/java/com/example/Calculator.java");
        assertThat(patch.hunks()).hasSize(1);

        Hunk hunk = patch.hunks().get(0);
        assertThat(hunk.oldStart()).isEqualTo(1);
        assertThat(hunk.oldCount()).isEqualTo(6);
        assertThat(hunk.newStart()).isEqualTo(1);
        assertThat(hunk.newCount()).isEqualTo(7);
        assertThat(hunk.lines()).extracting(HunkLine::type)
                .containsExactly(CONTEXT, CONTEXT, CONTEXT, ADD, CONTEXT, CONTEXT);
        assertThat(hunk.lines().get(3).content()).isEqualTo("    public int add(int a, int b) { return a + b; }");
        assertThat(hunk.lines().get(3).newLine()).isEqualTo(4);
        assertThat(hunk.lines().get(3).oldLine()).isNull();
        assertThat(hunk.lines().get(0).oldLine()).isEqualTo(1);
        assertThat(hunk.lines().get(0).newLine()).isEqualTo(1);
    }

    @Test
    void parsesNewFile() {
        String diff = String.join("\n",
                "diff --git a/src/test/java/com/example/CalculatorTest.java b/src/test/java/com/example/CalculatorTest.java",
                "new file mode 100644",
                "index 0000000..1111111",
                "--- /dev/null",
                "+++ b/src/test/java/com/example/CalculatorTest.java",
                "@@ -0,0 +1,3 @@",
                "+package com.example;",
                "+",
                "+class CalculatorTest {}");

        List<FilePatch> patches = parser.parse(diff);

        assertThat(patches).hasSize(1);
        FilePatch patch = patches.get(0);
        assertThat(patch.oldPath()).isEqualTo("/dev/null");
        assertThat(patch.newPath()).isEqualTo("src/test/java/com/example/CalculatorTest.java");
        Hunk hunk = patch.hunks().get(0);
        assertThat(hunk.oldStart()).isZero();
        assertThat(hunk.oldCount()).isZero();
        assertThat(hunk.newStart()).isEqualTo(1);
        assertThat(hunk.lines()).allSatisfy(line -> assertThat(line.type()).isEqualTo(ADD));
        assertThat(hunk.lines().get(0).newLine()).isEqualTo(1);
    }

    @Test
    void parsesMultipleFiles() {
        String diff = String.join("\n",
                "diff --git a/A.java b/A.java",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -1 +1,2 @@",
                "-old",
                "+new",
                "+extra",
                "diff --git a/B.java b/B.java",
                "--- a/B.java",
                "+++ b/B.java",
                "@@ -10,2 +10,3 @@",
                " ctx",
                "+added");

        List<FilePatch> patches = parser.parse(diff);

        assertThat(patches).hasSize(2);
        assertThat(patches.get(0).newPath()).isEqualTo("A.java");
        assertThat(patches.get(1).newPath()).isEqualTo("B.java");
        Hunk secondHunk = patches.get(1).hunks().get(0);
        assertThat(secondHunk.oldStart()).isEqualTo(10);
        assertThat(secondHunk.lines().get(1).newLine()).isEqualTo(11);
    }

    @Test
    void rejectsNonDiffInput() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse("just some text"))
                .isInstanceOf(DiffParseException.class);
    }
}
