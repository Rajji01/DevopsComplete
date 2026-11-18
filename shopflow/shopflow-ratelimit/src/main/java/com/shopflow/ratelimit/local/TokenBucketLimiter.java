package com.shopflow.ratelimit.local;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * TOKEN BUCKET — a bucket holds up to {@code capacity} tokens and refills at {@code refillPerSecond};
 * each request spends one token (or {@code permits}). Allows a controlled BURST: a client that was idle
 * can spend the whole bucket at once, then is held to the steady refill rate. This "save up then spend"
 * shape is why most API gateways (AWS, Stripe, GitHub) use it.
 *
 * We refill lazily — no background timer. On each call we add {@code elapsed * rate} tokens (capped at
 * capacity). That is exact and cheap: a key touched once an hour costs nothing in between.
 */
public final class TokenBucketLimiter implements RateLimiter {

    private record Bucket(double tokens, long ts) {}

    private final RateLimitRule rule;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Bucket> state = new ConcurrentHashMap<>();

    public TokenBucketLimiter(RateLimitRule rule, LongSupplier clock) {
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        double cap = rule.capacity();
        double rate = rule.refillPerSecond();
        RateLimitResult[] out = new RateLimitResult[1];
        state.compute(key, (k, b) -> {
            double tokens = (b == null) ? cap : b.tokens();
            long last = (b == null) ? now : b.ts();
            tokens = Math.min(cap, tokens + Math.max(0, now - last) / 1000.0 * rate); // lazy refill
            if (tokens >= permits) {
                double left = tokens - permits;
                out[0] = RateLimitResult.allow((long) Math.floor(left), (long) cap);
                return new Bucket(left, now);
            }
            long retry = (long) Math.ceil((permits - tokens) / rate * 1000); // ms until enough tokens
            out[0] = RateLimitResult.deny((long) Math.floor(tokens), (long) cap, retry);
            return new Bucket(tokens, now); // keep the refilled tokens, advance the clock
        });
        return out[0];
    }
}
