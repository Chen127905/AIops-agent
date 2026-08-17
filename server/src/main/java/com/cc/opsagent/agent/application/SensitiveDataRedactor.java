package com.cc.opsagent.agent.application;

import java.util.regex.Pattern;

final class SensitiveDataRedactor {

    private static final int MAX_SUMMARY_CHARACTERS = 512;
    private static final Pattern SECRET_TOKEN =
            Pattern.compile("(?i)sk-[a-z0-9_-]+");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(bearer\\s+)\\S+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_]?key|token|password|secret)\\s*[:=]\\s*)\\S+");

    private SensitiveDataRedactor() {
    }

    static String redact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String redacted = SECRET_TOKEN.matcher(value).replaceAll("[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
        return redacted.length() <= MAX_SUMMARY_CHARACTERS
                ? redacted
                : redacted.substring(0, MAX_SUMMARY_CHARACTERS);
    }
}
