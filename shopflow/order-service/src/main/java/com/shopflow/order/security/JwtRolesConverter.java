package com.shopflow.order.security;

import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Keycloak puts roles in "realm_access.roles", Amazon Cognito puts group names in "cognito:groups";
 * Spring expects ROLE_* authorities for hasRole(). Supporting both lets the same image run with
 * Keycloak in docker-compose/minikube and Cognito on AWS (only JWT_ISSUER_URI changes).
 */
@Component
public class JwtRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles;
        if (jwt.hasClaim("cognito:groups")) {
            roles = jwt.getClaimAsStringList("cognito:groups");
        } else {
            var realmAccess = jwt.getClaimAsMap("realm_access");
            roles = realmAccess == null ? List.of() : (List<String>) realmAccess.getOrDefault("roles", List.of());
        }
        Collection<GrantedAuthority> authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
