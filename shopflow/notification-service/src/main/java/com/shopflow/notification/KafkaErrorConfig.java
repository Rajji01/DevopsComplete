package com.shopflow.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Poison-pill handling: a record that keeps failing (bad JSON, DB down for too long) is retried
 * with backoff and then moved to "orders.events.DLT" (dead-letter topic) so it does not block
 * the partition forever. Someone looks at the DLT and replays or fixes it.
 *
 * Every successful dead-lettering increments kafka.dead.lettered (Walk 40): a record on the DLT
 * is a buried failure unless something tells a human — the KafkaMessageDeadLettered alert does.
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, MeterRegistry meterRegistry) {
        var deadLetter = new DeadLetterPublishingRecoverer(template);
        var deadLettered = Counter.builder("kafka.dead.lettered")
                .description("Records moved to a dead-letter topic after retries were exhausted")
                .register(meterRegistry);
        var backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        return new DefaultErrorHandler((record, ex) -> {
            deadLetter.accept(record, ex); // throws if the DLT publish fails -> record retried, not counted
            deadLettered.increment();
        }, backOff);
    }
}
