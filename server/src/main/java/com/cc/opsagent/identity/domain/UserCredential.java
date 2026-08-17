package com.cc.opsagent.identity.domain;

public record UserCredential(
        long userId,
        long tenantId,
        String username,
        String passwordHash,
        String role) {
}
