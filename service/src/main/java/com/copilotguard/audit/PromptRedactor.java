package com.copilotguard.audit;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptRedactor {

    private static final List<NamedPattern> PATTERNS = List.of(
            new NamedPattern("anthropic-api-key", "(?i)sk-ant-[A-Za-z0-9_-]{8,}"),
            new NamedPattern("github-token", "(?i)\\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{10,}\\b"),
            new NamedPattern("aws-access-key", "\\bAKIA[0-9A-Z]{16}\\b"),
            new NamedPattern("private-key-block",
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", Pattern.DOTALL),
            new NamedPattern("bearer-token", "(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
            new NamedPattern("password-assignment",
                    "(?i)\\b(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*(?!\"?\\[REDACTED:)[^\\s,}]+")
    );

    public RedactionResult redact(String prompt) {
        String redacted = prompt;
        List<String> hits = new ArrayList<>();
        for (NamedPattern namedPattern : PATTERNS) {
            Matcher matcher = namedPattern.pattern().matcher(redacted);
            if (matcher.find()) {
                redacted = matcher.replaceAll("[REDACTED:" + namedPattern.name() + "]");
                hits.add(namedPattern.name());
            }
        }
        return new RedactionResult(redacted, List.copyOf(hits));
    }

    private record NamedPattern(String name, Pattern pattern) {

        private NamedPattern(String name, String regex) {
            this(name, Pattern.compile(regex));
        }

        private NamedPattern(String name, String regex, int flags) {
            this(name, Pattern.compile(regex, flags));
        }
    }
}
