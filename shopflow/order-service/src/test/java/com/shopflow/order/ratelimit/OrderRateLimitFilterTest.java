package com.shopflow.order.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.ratelimit.RateLimitAlgorithm;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;
import com.shopflow.ratelimit.RateLimiterFactory;

/**
 * The filter in isolation: a real in-memory limiter (fixed window, 2 per window, frozen clock so the
 * window never rolls) behind a stub controller, driven over MockMvc. Deterministic — no Spring context,
 * no saga, no Redis. Proves the three things the edge contract promises: creates are limited, the limit
 * is only on the POST write, and a 429 carries the standard back-off headers.
 */
class OrderRateLimitFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 2 creates per (1-hour) window; the clock is frozen so the window never rolls during the test
        RateLimiter limiter = RateLimiterFactory.local(
                RateLimitAlgorithm.FIXED_WINDOW,
                RateLimitRule.perWindow(2, Duration.ofHours(1)),
                () -> 1_000L);
        OrderRateLimitFilter filter = new OrderRateLimitFilter(limiter, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController()).addFilter(filter).build();
    }

    @Test
    void burstOverTheLimitGets429WithBackoffHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/orders"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
        mockMvc.perform(post("/api/v1/orders"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        mockMvc.perform(post("/api/v1/orders"))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void readsAreNeverLimited() throws Exception {
        // five GETs well past the create limit all pass — the filter only guards the POST write
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/orders")).andExpect(status().isOk());
        }
    }

    @RestController
    @RequestMapping("/api/v1/orders")
    static class StubController {

        @PostMapping
        ResponseEntity<String> create() {
            return ResponseEntity.status(HttpStatus.CREATED).body("created");
        }

        @GetMapping
        String list() {
            return "ok";
        }
    }
}
