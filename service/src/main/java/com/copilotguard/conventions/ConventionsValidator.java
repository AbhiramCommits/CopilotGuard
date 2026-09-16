package com.copilotguard.conventions;

import com.copilotguard.domain.Severity;
import com.copilotguard.llm.GeneratedTestFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConventionsValidator {

    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default|native)\\s+)*"
                    + "(?:[\\w<>\\[\\].?]+\\s+)+(\\w+)\\s*\\([^;{}]*\\)(?:\\s*throws\\s+[\\w.,\\s]+)?\\s*(\\{?)");
    private static final Set<String> NON_METHOD_TOKENS =
            Set.of("if", "for", "while", "switch", "catch", "do", "else", "return");

    public List<ConventionViolation> validate(List<GeneratedTestFile> files, CopilotGuardConventions conventions) {
        List<ConventionViolation> violations = new ArrayList<>();
        for (GeneratedTestFile file : files) {
            violations.addAll(validateFile(file, conventions));
        }
        return violations;
    }

    private List<ConventionViolation> validateFile(GeneratedTestFile file, CopilotGuardConventions conventions) {
        List<ConventionViolation> violations = new ArrayList<>();
        String content = file.content();

        Matcher classMatcher = CLASS_DECL.matcher(content);
        if (!classMatcher.find()) {
            violations.add(new ConventionViolation(file.path(), null, Severity.MAJOR,
                    "no class declaration found in generated test file"));
        } else if (!classMatcher.group(1).matches(conventions.testClassNamePattern())) {
            violations.add(new ConventionViolation(file.path(), null, Severity.MAJOR,
                    "class name '" + classMatcher.group(1) + "' does not match testClassNamePattern '"
                            + conventions.testClassNamePattern() + "'"));
        }

        String[] lines = content.split("\n", -1);
        for (String banned : conventions.bannedApis()) {
            Pattern pattern = Pattern.compile(banned);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    violations.add(new ConventionViolation(file.path(), i + 1, Severity.BLOCKER,
                            "banned API '" + banned + "' used at line " + (i + 1)));
                }
            }
        }

        for (String annotation : conventions.requiredTestAnnotations()) {
            String simpleName = annotation.contains(".")
                    ? annotation.substring(annotation.lastIndexOf('.') + 1)
                    : annotation;
            if (!content.contains("@" + simpleName)) {
                violations.add(new ConventionViolation(file.path(), null, Severity.MAJOR,
                        "required test annotation '" + annotation + "' missing"));
            }
        }

        Matcher methodMatcher = METHOD_DECL.matcher(content);
        while (methodMatcher.find()) {
            String name = methodMatcher.group(1);
            if (NON_METHOD_TOKENS.contains(name)) {
                continue;
            }
            int brace = findOpeningBrace(content, methodMatcher);
            if (brace < 0) {
                continue;
            }
            int length = measureMethodLength(content, brace);
            if (length > conventions.maxMethodLength()) {
                violations.add(new ConventionViolation(file.path(), lineNumberAt(content, methodMatcher.start()),
                        Severity.MINOR, "method '" + name + "' is " + length + " lines long, exceeding maxMethodLength "
                                + conventions.maxMethodLength()));
            }
        }
        return violations;
    }

    private static int findOpeningBrace(String content, Matcher matcher) {
        String brace = matcher.group(2);
        if (brace != null && !brace.isEmpty()) {
            return matcher.end() - 1;
        }
        for (int i = matcher.end(); i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') {
                return i;
            }
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                return -1;
            }
        }
        return -1;
    }

    private static int measureMethodLength(String content, int bracePosition) {
        int depth = 0;
        int end = -1;
        for (int i = bracePosition; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            return 0;
        }
        return content.substring(bracePosition, end + 1).split("\n", -1).length;
    }

    private static int lineNumberAt(String content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
