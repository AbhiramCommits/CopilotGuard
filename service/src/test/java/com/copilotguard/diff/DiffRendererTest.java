package com.copilotguard.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffRendererTest {

    private final DiffRenderer renderer = new DiffRenderer();

    @Test
    void rendersPatchesWithHunkHeadersAndPrefixes() {
        List<FilePatch> patches =
                new UnifiedDiffParser()
                        .parse(
                                String.join(
                                        "\n",
                                        "diff --git a/A.java b/A.java",
                                        "--- a/A.java",
                                        "+++ b/A.java",
                                        "@@ -1,2 +1,3 @@",
                                        " one",
                                        "+two",
                                        " three"));

        String rendered = renderer.render(patches);

        assertThat(rendered).contains("### A.java");
        assertThat(rendered).contains("@@ -1,2 +1,3 @@");
        assertThat(rendered).contains(" one").contains("+two").contains(" three");
    }

    @Test
    void skipsDeletedFiles() {
        List<FilePatch> patches =
                new UnifiedDiffParser()
                        .parse(
                                String.join(
                                        "\n",
                                        "diff --git a/Gone.java b/Gone.java",
                                        "deleted file mode 100644",
                                        "--- a/Gone.java",
                                        "+++ /dev/null",
                                        "@@ -1,1 +0,0 @@",
                                        "-class Gone {}"));

        String rendered = renderer.render(patches);

        assertThat(rendered).contains("### Gone.java");
        assertThat(rendered).doesNotContain("/dev/null");
        assertThat(rendered).contains("-class Gone {}");
    }
}
