package com.shopflow.order.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Boot's default decoder checks signature, expiry and issuer. A token minted for ANOTHER API by
 * the same issuer would still pass, so the audience is pinned too when one is configured:
 * Keycloak names the client in "aud" or "azp", Cognito access tokens in "client_id".
 * Empty = not checked (local dev). The issuer's metadata is fetched lazily (SupplierJwtDecoder),
 * so the service still starts when the identity provider is down.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, @Value("${jwt.audience:}") String audience) {
        String issuer = properties.getJwt().getIssuerUri();
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
            decoder.setJwtValidator(audience.isBlank()
                    ? withIssuer
                    : new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator(audience)));
            return decoder;
        });
    }

    /** accept the token if ANY of aud / azp / client_id names this API */
    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            boolean ok = (aud != null && aud.contains(audience))
                    || audience.equals(jwt.getClaimAsString("azp"))
                    || audience.equals(jwt.getClaimAsString("client_id"));
            return ok ? OAuth2TokenValidatorResult.success()
                      : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            "token was not issued for this API (audience " + audience + ")", null));
        };
    }
}
