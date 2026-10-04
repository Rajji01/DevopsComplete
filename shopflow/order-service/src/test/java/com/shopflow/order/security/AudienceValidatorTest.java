package com.shopflow.order.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private static Jwt token(Consumer<Jwt.Builder> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.accept(b);
        return b.build();
    }

    @Test
    void acceptsKeycloakAndCognitoShapes() {
        var validator = JwtDecoderConfig.audienceValidator("shopflow-web");
        assertThat(validator.validate(token(b -> b.audience(List.of("shopflow-web")))).hasErrors()).isFalse();
        assertThat(validator.validate(token(b -> b.audience(List.of("account")).claim("azp", "shopflow-web"))).hasErrors()).isFalse();
        assertThat(validator.validate(token(b -> b.claim("client_id", "shopflow-web"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokensMintedForAnotherApi() {
        var validator = JwtDecoderConfig.audienceValidator("shopflow-web");
        var result = validator.validate(token(b -> b.audience(List.of("billing-api")).claim("azp", "billing-web")));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getDescription()).contains("audience shopflow-web");
    }
}
