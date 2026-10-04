package com.shopflow.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * order-service is an OAuth2 resource server: it only validates JWTs issued by Keycloak
 * (signature via the issuer's JWKS, expiry, issuer). It never sees passwords and keeps no session.
 * Roles come from the token: the Keycloak client maps realm roles to the "roles" claim.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtRolesConverter rolesConverter) throws Exception {
        return http
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
                .build();
    }
}
