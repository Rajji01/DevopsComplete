package com.shopflow.order.order;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Who is calling: the JWT subject, and whether they hold the support role (may read every order). */
public record Caller(String customerId, boolean support) {

    public static Caller from(Authentication authentication) {
        boolean support = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_support"::equals);
        return new Caller(authentication.getName(), support);
    }

    boolean mayAccess(Order order) {
        return support || order.isOwnedBy(customerId);
    }
}
