package com.shopflow.ratelimit;

/**
 * One operation: "may this key do {@code permits} units of work right now?" — decided and recorded
 * atomically. {@code key} is whatever you limit on: a user id, an API key, a client IP, or a tuple
 * like "userId:endpoint". Every algorithm and every backend implements exactly this.
 */
public interface RateLimiter {

    RateLimitResult tryAcquire(String key, int permits);

    default RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }
}
