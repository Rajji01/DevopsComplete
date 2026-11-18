package com.shopflow.resilience;

import java.util.Random;

/**
 * Retry backoff strategies. The progression matters under load:
 *  fixed        — same wait every time. Simple, but N clients that failed together retry together again.
 *  exponential  — double each attempt (capped). Backs off fast, but still synchronised — all the retriers
 *                 wake at the same instants and re-stampede the recovering service (the "thundering herd").
 *  full jitter  — random in [0, exp]. Spreads retries out; the AWS Architecture Blog's recommended default.
 *  equal jitter — random in [exp/2, exp]. Keeps some minimum backoff while still spreading.
 */
public final class Backoffs {

    private Backoffs() {}

    public static BackoffStrategy fixed(long millis) {
        return attempt -> millis;
    }

    public static BackoffStrategy exponential(long baseMillis, long capMillis) {
        return attempt -> cappedExp(baseMillis, capMillis, attempt);
    }

    public static BackoffStrategy fullJitter(long baseMillis, long capMillis, Random random) {
        return attempt -> {
            long exp = cappedExp(baseMillis, capMillis, attempt);
            return exp <= 0 ? 0 : random.nextLong(exp + 1);          // [0, exp]
        };
    }

    public static BackoffStrategy equalJitter(long baseMillis, long capMillis, Random random) {
        return attempt -> {
            long exp = cappedExp(baseMillis, capMillis, attempt);
            long half = exp / 2;
            return half + (half <= 0 ? 0 : random.nextLong(half + 1)); // [exp/2, exp]
        };
    }

    private static long cappedExp(long base, long cap, int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);          // guard against overflow on huge attempts
        long value = base << shift;
        return (value < 0 || value > cap) ? cap : value;
    }
}
