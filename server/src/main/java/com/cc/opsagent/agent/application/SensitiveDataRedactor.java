package com.cc.opsagent.agent.application;

final class SensitiveDataRedactor {

    private static final int MAX_SUMMARY_CHARACTERS = 512;
    private static final com.cc.opsagent.security.SensitiveDataRedactor DELEGATE =
            new com.cc.opsagent.security.SensitiveDataRedactor();

    private SensitiveDataRedactor() {
    }

    static String redact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return DELEGATE.redact(value, MAX_SUMMARY_CHARACTERS);
    }
}
