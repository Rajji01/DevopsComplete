package com.shopflow.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.order.ratelimit.OrderRateLimitFilter;
import com.shopflow.ratelimit.RateLimitConfiguration;
import com.shopflow.ratelimit.RateLimiter;

/**
 * order-service is an OAuth2 resource server: it only validates JWTs issued by Keycloak
 * (signature via the issuer's JWKS, expiry, issuer). It never sees passwords and keeps no session.
 * Roles come from the token: the Keycloak client maps realm roles to the "roles" claim.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Import(RateLimitConfiguration.class)   // the shared RateLimiter bean + ratelimit.* properties
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtRolesConverter rolesConverter,
                                    RateLimiter rateLimiter, ObjectMapper objectMapper) throws Exception {
        return http
                .cors(Customizer.withDefaults())         // honour the CorsConfigurationSource bean (CorsConfig) — dev only
                .csrf(csrf -> csrf.disable())           // no cookies/session -> no CSRF surface
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // probes and metrics are called by kubelet/Prometheus without a token;
                        // NetworkPolicy + ingress rules keep them off the public internet
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("customer", "support")
                        .requestMatchers("/api/v1/orders/**").hasRole("customer")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter)))
                // just before authorization (so authentication has already set the JWT principal, but a
                // flood is still thrown out early): the filter only acts on POST /api/v1/orders and keys
                // per customer — shared across pods when backend=REDIS, unlike the per-pod resilience4j cap
                .addFilterBefore(new OrderRateLimitFilter(rateLimiter, objectMapper), AuthorizationFilter.class)
                .build();
    }
}
