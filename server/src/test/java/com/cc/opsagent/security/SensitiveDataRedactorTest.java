package com.cc.opsagent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    @Test
    void removesBuiltInAndConfiguredSecretsWithoutLoggingTheirValues() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(
                "tenant-secret-[0-9]+;;private-[A-Z]+");

        String result = redactor.redact("""
                Authorization: Bearer abc.def.ghi
                api_key=sk-live-abcdef123456
                password=hunter2
                tenant-secret-7788 private-KEY
                """);

        assertThat(result)
                .doesNotContain("abc.def.ghi", "sk-live-abcdef123456", "hunter2",
                        "tenant-secret-7788", "private-KEY")
                .contains("[REDACTED]");
    }
}
