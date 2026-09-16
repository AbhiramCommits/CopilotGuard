package com.copilotguard.conventions;

import com.copilotguard.domain.Severity;
import com.copilotguard.llm.GeneratedTestFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConventionsValidatorTest {

    private final ConventionsValidator validator = new ConventionsValidator();

    @Test
    void flagsBannedApisNamingAnnotationsAndMethodLength() {
        CopilotGuardConventions conventions = new CopilotGuardConventions(
                ".*Test$", List.of("System\\.exit", "Thread\\.sleep"), List.of("org.junit.jupiter.api.Test"), 3);
        GeneratedTestFile file = new GeneratedTestFile("src/test/java/com/example/FooSpec.java", String.join("\n",
                "package com.example;",
                "",
                "class FooSpec {",
                "    void longMethod() {",
                "        int a = 1;",
                "        int b = 2;",
                "        int c = 3;",
                "        int d = 4;",
                "    }",
                "",
                "    void banned() {",
                "        System.exit(0);",
                "    }",
                "}",
                ""));

        List<ConventionViolation> violations = validator.validate(List.of(file), conventions);

        assertThat(violations).hasSize(4);
        assertThat(violations).extracting(ConventionViolation::message)
                .anyMatch(message -> message.contains("banned API 'System\\.exit'"))
                .anyMatch(message -> message.contains("testClassNamePattern"))
                .anyMatch(message -> message.contains("required test annotation"))
                .anyMatch(message -> message.contains("maxMethodLength"));
        assertThat(violations).filteredOn(v -> v.message().contains("banned API"))
                .allSatisfy(v -> assertThat(v.severity()).isEqualTo(Severity.BLOCKER));
        assertThat(violations).filteredOn(v -> v.message().contains("maxMethodLength"))
                .allSatisfy(v -> assertThat(v.severity()).isEqualTo(Severity.MINOR));
    }

    @Test
    void passesCompliantFiles() {
        CopilotGuardConventions conventions = new CopilotGuardConventions(
                ".*(Test|IT)$", List.of("System\\.exit"), List.of("org.junit.jupiter.api.Test"), 100);
        GeneratedTestFile file = new GeneratedTestFile("src/test/java/com/example/CalculatorTest.java", String.join("\n",
                "package com.example;",
                "",
                "import org.junit.jupiter.api.Test;",
                "",
                "class CalculatorTest {",
                "",
                "    @Test",
                "    void adds() {",
                "        org.junit.jupiter.api.Assertions.assertEquals(4, 2 + 2);",
                "    }",
                "}",
                ""));

        assertThat(validator.validate(List.of(file), conventions)).isEmpty();
    }
}
