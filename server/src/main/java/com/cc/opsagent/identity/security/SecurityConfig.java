package com.cc.opsagent.identity.security;

import com.cc.opsagent.audit.AuditService;
import com.cc.opsagent.observability.CorrelationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorrelationFilter correlationFilter,
            AuditService audit) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            audit.recordAuthenticated(
                                    "AUTHENTICATION_REQUIRED", "REJECTED",
                                    "HTTP_REQUEST", request.getRequestURI(),
                                    Map.of("reason", "AUTHENTICATION_REQUIRED"));
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            audit.recordAuthenticated(
                                    "AUTHORIZATION_DENIED", "REJECTED",
                                    "HTTP_REQUEST", request.getRequestURI(),
                                    Map.of("reason", "INSUFFICIENT_ROLE"));
                            response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/auth/login",
                                "/actuator/health",
                                "/error").permitAll()
                        .requestMatchers(
                                "/actuator/info",
                                "/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(correlationFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
