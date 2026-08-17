package com.cc.opsagent.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET =
            "test-only-jwt-secret-with-at-least-thirty-two-characters";
    private static final TenantPrincipal PRINCIPAL =
            new TenantPrincipal(10L, 20L, "alice", Set.of("OPERATOR"));

    @Test
    void rejectsTokenIssuedByAnotherSystem() {
        JwtService trustedService = new JwtService(
                SECRET, "ops-agent-platform", Duration.ofHours(2));
        JwtService otherIssuer = new JwtService(
                SECRET, "another-system", Duration.ofHours(2));

        String token = otherIssuer.issue(PRINCIPAL);

        assertThatThrownBy(() -> trustedService.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws JOSEException {
        JwtService service = new JwtService(
                SECRET, "ops-agent-platform", Duration.ofHours(2));

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("ops-agent-platform")
                .issueTime(Date.from(now.minus(Duration.ofMinutes(3))))
                .expirationTime(Date.from(now.minus(Duration.ofMinutes(2))))
                .subject("20")
                .claim("tenant_id", 10L)
                .claim("username", "alice")
                .claim("roles", List.of("OPERATOR"))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.parse(signedJwt.serialize()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSignedTokenWithoutTenantIdentity() throws JOSEException {
        JwtService service = new JwtService(
                SECRET, "ops-agent-platform", Duration.ofHours(2));
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("ops-agent-platform")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .subject("20")
                .claim("username", "alice")
                .claim("roles", List.of("OPERATOR"))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.parse(signedJwt.serialize()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithInvalidSignature() throws JOSEException {
        JwtService service = new JwtService(
                SECRET, "ops-agent-platform", Duration.ofHours(2));
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("ops-agent-platform")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .subject("20")
                .claim("tenant_id", 10L)
                .claim("username", "alice")
                .claim("roles", List.of("OPERATOR"))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(
                "different-test-secret-with-at-least-thirty-two-characters"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.parse(signedJwt.serialize()))
                .isInstanceOf(JwtException.class);
    }
}
