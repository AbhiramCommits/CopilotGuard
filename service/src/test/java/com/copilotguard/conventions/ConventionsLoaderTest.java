package com.copilotguard.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConventionsLoaderTest {

    private final ConventionsLoader loader = new ConventionsLoader();

    @Test
    void returnsDefaultsForBlankInput() {
        CopilotGuardConventions conventions = loader.parse("");

        assertThat(conventions.testClassNamePattern()).isEqualTo(".*(Test|IT|ITs|Tests)$");
        assertThat(conventions.bannedApis()).isEmpty();
        assertThat(conventions.requiredTestAnnotations())
                .containsExactly("org.junit.jupiter.api.Test");
        assertThat(conventions.maxMethodLength()).isEqualTo(100);
    }

    @Test
    void parsesFullYaml() {
        CopilotGuardConventions conventions =
                loader.parse(
                        "naming:\n"
                                + "  testClassNamePattern: \".*Spec$\"\n"
                                + "bannedApis:\n"
                                + "  - \"System\\\\.exit\"\n"
                                + "  - \"Thread\\\\.sleep\"\n"
                                + "requiredTestAnnotations:\n"
                                + "  - org.junit.jupiter.api.Test\n"
                                + "  - com.example.IntegrationTest\n"
                                + "maxMethodLength: 42\n");

        assertThat(conventions.testClassNamePattern()).isEqualTo(".*Spec$");
        assertThat(conventions.bannedApis()).containsExactly("System\\.exit", "Thread\\.sleep");
        assertThat(conventions.requiredTestAnnotations())
                .containsExactly("org.junit.jupiter.api.Test", "com.example.IntegrationTest");
        assertThat(conventions.maxMethodLength()).isEqualTo(42);
    }

    @Test
    void partialYamlKeepsDefaults() {
        CopilotGuardConventions conventions =
                loader.parse("bannedApis:\n  - \"System\\\\.exit\"\n");

        assertThat(conventions.bannedApis()).containsExactly("System\\.exit");
        assertThat(conventions.testClassNamePattern()).isEqualTo(".*(Test|IT|ITs|Tests)$");
        assertThat(conventions.maxMethodLength()).isEqualTo(100);
    }

    @Test
    void nonMapYamlFallsBackToDefaults() {
        CopilotGuardConventions conventions = loader.parse("- just\n- a\n- list\n");

        assertThat(conventions.testClassNamePattern()).isEqualTo(".*(Test|IT|ITs|Tests)$");
    }
}
