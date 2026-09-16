package com.copilotguard.conventions;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

@Component
public class ConventionsLoader {

    public static CopilotGuardConventions defaults() {
        return new CopilotGuardConventions(
                ".*(Test|IT|ITs|Tests)$", List.of(), List.of("org.junit.jupiter.api.Test"), 100);
    }

    public CopilotGuardConventions parse(String yamlText) {
        CopilotGuardConventions defaults = defaults();
        if (!StringUtils.hasText(yamlText)) {
            return defaults;
        }
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> map)) {
            return defaults;
        }
        String pattern = defaults.testClassNamePattern();
        List<String> bannedApis = defaults.bannedApis();
        List<String> annotations = defaults.requiredTestAnnotations();
        int maxMethodLength = defaults.maxMethodLength();
        if (map.get("naming") instanceof Map<?, ?> naming
                && naming.get("testClassNamePattern") instanceof String s) {
            pattern = s;
        }
        if (map.get("bannedApis") instanceof List<?> list) {
            bannedApis = list.stream().map(String::valueOf).toList();
        }
        if (map.get("requiredTestAnnotations") instanceof List<?> list) {
            annotations = list.stream().map(String::valueOf).toList();
        }
        if (map.get("maxMethodLength") instanceof Number number) {
            maxMethodLength = number.intValue();
        }
        return new CopilotGuardConventions(
                pattern, List.copyOf(bannedApis), List.copyOf(annotations), maxMethodLength);
    }
}
