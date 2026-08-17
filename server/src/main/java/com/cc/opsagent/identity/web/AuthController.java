package com.cc.opsagent.identity.web;

import com.cc.opsagent.identity.application.UserAuthenticator;
import com.cc.opsagent.identity.security.JwtService;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.identity.security.TenantPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAuthenticator authenticator;
    private final JwtService jwtService;

    public AuthController(
            UserAuthenticator authenticator,
            JwtService jwtService) {
        this.authenticator = authenticator;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        TenantPrincipal principal = authenticator.authenticate(
                request.tenantCode(),
                request.username(),
                request.password());
        return new TokenResponse(
                jwtService.issue(principal),
                "Bearer",
                jwtService.expiresInSeconds());
    }

    @GetMapping("/me")
    public MeResponse me() {
        TenantPrincipal principal = TenantContext.requirePrincipal();
        return new MeResponse(
                principal.tenantId(),
                principal.userId(),
                principal.username(),
                principal.roles());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse badCredentials() {
        return new ErrorResponse("INVALID_CREDENTIALS");
    }

    public record LoginRequest(
            @NotBlank String tenantCode,
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds) {
    }

    public record MeResponse(
            long tenantId,
            long userId,
            String username,
            Set<String> roles) {
    }

    public record ErrorResponse(String code) {
    }
}
