package com.shopflow.order.ratelimit;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rate-limits the one expensive write — {@code POST /api/v1/orders} — because that single call kicks
 * off the whole reserve -> charge -> notify saga (an inventory HTTP reservation, an outbox row, a
 * Kafka event). Reads and every other route pass straight through.
 *
 * <p>The limit is applied PER CUSTOMER (the JWT {@code sub}), so one abusive client can't starve the
 * others, and — when {@code ratelimit.backend=REDIS} — the allowance is shared across all pods rather
 * than per-pod (the gap this replaces: resilience4j's {@code orders} limiter is 50/s PER POD, so N pods
 * silently allow 50N/s). It runs AFTER the bearer-token authentication filter so the authenticated
 * principal is already in the {@link SecurityContextHolder}; an unauthenticated caller (which Security
 * will reject a step later anyway) is keyed by remote address so it still can't spin the limiter for free.
 *
 * <p>Not a Spring bean on purpose: it is constructed in {@code SecurityConfig} and inserted into the
 * security chain, which avoids Boot also auto-registering it as a plain servlet filter (double-run).
 */
public class OrderRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimitFilter.class);
    private static final String ORDERS_PATH = "/api/v1/orders";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public OrderRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // only the order-create write; the trailing-slash form and sub-paths are not creates
        return !(HttpMethod.POST.matches(request.getMethod()) && ORDERS_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = clientKey(request);
        RateLimitResult result = rateLimiter.tryAcquire(key);

        // headers on every response let a well-behaved client self-throttle before it gets a 429
        response.setHeader("X-RateLimit-Limit", Long.toString(result.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(result.remaining()));

        if (result.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = result.retryAfterSeconds();
        log.info("rate limit hit on order-create for {} -> 429 (retry after {}s)", key, retryAfter);
        response.setHeader("Retry-After", Long.toString(retryAfter)); // RFC 7231: whole seconds
        response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "rate_limited",
                "message", "Too many order requests — slow down and retry",
                "retryAfterSeconds", retryAfter)));
    }

    /** Per-customer key (JWT sub); falls back to the caller IP when there is no authenticated principal. */
    private String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "order-create:sub:" + auth.getName();
        }
        return "order-create:ip:" + request.getRemoteAddr();
    }
}
