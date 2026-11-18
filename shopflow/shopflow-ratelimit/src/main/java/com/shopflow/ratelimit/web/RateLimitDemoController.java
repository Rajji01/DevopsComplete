package com.shopflow.ratelimit.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.shopflow.ratelimit.RateLimitProperties;
import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The live target. Hammer it and watch the 429s:
 *   for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " -H 'X-Client-Id: alice' localhost:8086/api/ping; done; echo
 *
 * The limit is PER key. Key = the {@code X-Client-Id} header if present, else the caller's IP — so two
 * clients are limited independently. {@code GET /api/config} shows which algorithm/backend is live.
 */
@RestController
public class RateLimitDemoController {

    private final RateLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitDemoController(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @GetMapping("/api/ping")
    public ResponseEntity<Map<String, Object>> ping(
            HttpServletRequest request,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId) {

        String key = (clientId != null && !clientId.isBlank()) ? clientId : request.getRemoteAddr();
        RateLimitResult r = limiter.tryAcquire(key);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Limit", Long.toString(r.limit()));
        headers.add("X-RateLimit-Remaining", Long.toString(r.remaining()));

        if (!r.allowed()) {
            headers.add("Retry-After", Long.toString(r.retryAfterSeconds())); // RFC 7231: whole seconds
            return new ResponseEntity<>(
                    Map.of("allowed", false, "key", key, "retryAfterMs", r.retryAfterMillis()),
                    headers, HttpStatus.TOO_MANY_REQUESTS);
        }
        return new ResponseEntity<>(
                Map.of("allowed", true, "key", key, "remaining", r.remaining()),
                headers, HttpStatus.OK);
    }

    /** What is live right now — so you can confirm a config switch took effect. */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of(
                "algorithm", props.getAlgorithm(),
                "backend", props.getBackend(),
                "limit", props.getLimit(),
                "windowMs", props.getWindow().toMillis(),
                "capacity", props.getCapacity(),
                "refillPerSecond", props.getRefillPerSecond(),
                "failOpen", props.isFailOpen());
    }
}
