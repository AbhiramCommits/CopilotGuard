package com.copilotguard.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PromptRedactor {

    private static final Pattern CARD_PATTERN = Pattern.compile("\\b\\d[0-9 -]{11,18}\\d\\b");

    private static final List<Detector> DETECTORS =
            List.of(
                    new Detector(
                            "private_key",
                            true,
                            Pattern.compile(
                                    "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
                                    Pattern.DOTALL)),
                    new Detector(
                            "anthropic_api_key",
                            true,
                            Pattern.compile("(?i)sk-ant-[A-Za-z0-9_-]{8,}")),
                    new Detector(
                            "github_token",
                            true,
                            Pattern.compile(
                                    "(?i)\\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{10,}\\b")),
                    new Detector(
                            "aws_key", true, Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
                    new Detector(
                            "jwt",
                            true,
                            Pattern.compile(
                                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")),
                    new Detector(
                            "bearer_token",
                            true,
                            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")),
                    new Detector(
                            "connection_string",
                            true,
                            Pattern.compile(
                                    "(?i)\\b(?:mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|redis|jdbc:[a-z]+)://[^\\s/:]+:[^\\s@/]+@[^\\s]+")),
                    new Detector(
                            "password_assignment",
                            true,
                            Pattern.compile(
                                    "(?i)\\b(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*(?!\"?\\[REDACTED:)[^\\s,}]+")),
                    new Detector(
                            "email",
                            false,
                            Pattern.compile(
                                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")),
                    new Detector("ssn", false, Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")));

    public RedactionResult redact(String text) {
        String redacted = text;
        List<RedactionHit> hits = new ArrayList<>();
        for (Detector detector : DETECTORS) {
            Outcome outcome = redactPattern(redacted, detector.pattern(), detector.name());
            if (outcome.changed()) {
                redacted = outcome.text();
                hits.add(new RedactionHit(detector.name(), detector.blocker()));
            }
        }
        Outcome cardOutcome = redactCards(redacted);
        if (cardOutcome.changed()) {
            redacted = cardOutcome.text();
            hits.add(new RedactionHit("card", false));
        }
        return new RedactionResult(redacted, List.copyOf(hits));
    }

    private static Outcome redactPattern(String text, Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return new Outcome(text, false);
        }
        String placeholder = "[REDACTED:" + name.toUpperCase(Locale.ROOT) + ":";
        StringBuffer sb = new StringBuffer();
        int count = 0;
        do {
            count++;
            matcher.appendReplacement(sb, placeholder + count + "]");
        } while (matcher.find());
        matcher.appendTail(sb);
        return new Outcome(sb.toString(), true);
    }

    private static Outcome redactCards(String text) {
        Matcher matcher = CARD_PATTERN.matcher(text);
        if (!matcher.find()) {
            return new Outcome(text, false);
        }
        StringBuffer sb = new StringBuffer();
        int count = 0;
        boolean changed = false;
        do {
            String candidate = matcher.group();
            String digits = candidate.replace(" ", "").replace("-", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                count++;
                changed = true;
                matcher.appendReplacement(sb, "[REDACTED:CARD:" + count + "]");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(candidate));
            }
        } while (matcher.find());
        if (!changed) {
            return new Outcome(text, false);
        }
        matcher.appendTail(sb);
        return new Outcome(sb.toString(), true);
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private record Detector(String name, boolean blocker, Pattern pattern) {}

    private record Outcome(String text, boolean changed) {}
}
