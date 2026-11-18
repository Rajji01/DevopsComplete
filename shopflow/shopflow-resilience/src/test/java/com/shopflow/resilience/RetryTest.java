package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RetryTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final Retry.Sleeper recorder = sleeps::add; // records backoff waits instead of sleeping

    @Test
    void succeedsAfterTransientFailures() throws Exception {
        var calls = new AtomicInteger();
        var retry = new Retry(3, Backoffs.fixed(10), e -> true, recorder);
        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(10L, 10L); // two backoffs between the three attempts
    }

    @Test
    void exhaustsAttemptsThenRethrows() {
        var retry = new Retry(3, Backoffs.fixed(5), e -> true, recorder);
        assertThatThrownBy(() -> retry.execute(() -> {
            throw new RuntimeException("always");
        })).hasMessage("always");
        assertThat(sleeps).hasSize(2); // 3 attempts = 2 backoffs
    }

    @Test
    void doesNotRetryWhatIsNotRetryable() {
        var retry = new Retry(3, Backoffs.fixed(5), e -> e instanceof IllegalStateException, recorder);
        assertThatThrownBy(() -> retry.execute(() -> {
            throw new IllegalArgumentException("validation");       // not retryable per the predicate
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(sleeps).isEmpty();                               // failed once, never retried
    }
}
