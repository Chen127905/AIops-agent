package com.cc.opsagent.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SensitiveDataRedactor {

    private static final String MASK = "[REDACTED]";
    private final List<Rule> rules;

    public SensitiveDataRedactor() {
        this("");
    }

    @Autowired
    public SensitiveDataRedactor(
            @Value("${app.security.redaction.additional-patterns:}")
            String additionalPatterns) {
        List<Rule> configured = new ArrayList<>();
        configured.add(new Rule(
                Pattern.compile("(?i)sk-[a-z0-9_-]{6,}"), false));
        configured.add(new Rule(
                Pattern.compile("(?i)(bearer\\s+)\\S+"), true));
        configured.add(new Rule(Pattern.compile(
                "(?i)((?:api[-_]?key|access[-_]?token|refresh[-_]?token|password|secret)"
                        + "\\s*[:=]\\s*)[^\\s,;]+"), true));
        if (additionalPatterns != null && !additionalPatterns.isBlank()) {
            for (String expression : additionalPatterns.split(";;")) {
                if (!expression.isBlank()) {
                    configured.add(new Rule(
                            Pattern.compile(expression.trim()), false));
                }
            }
        }
        rules = List.copyOf(configured);
    }

    public String redact(String value) {
        return redact(value, Integer.MAX_VALUE);
    }

    public String redact(String value, int maxCharacters) {
        if (value == null) return null;
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("redaction length must be positive");
        }
        String redacted = value;
        for (Rule rule : rules) {
            redacted = rule.pattern().matcher(redacted).replaceAll(match ->
                    rule.preservePrefix()
                            && match.groupCount() > 0 && match.group(1) != null
                            ? match.group(1) + MASK : MASK);
        }
        return redacted.length() <= maxCharacters
                ? redacted : redacted.substring(0, maxCharacters);
    }

    private record Rule(Pattern pattern, boolean preservePrefix) { }
}
