package com.shopflow.ledger.saga;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Same poison-pill policy as the other consumers: retry with backoff, then dead-letter
 * (payments.events.DLT) so one unprocessable record never blocks the ledger partition. Each
 * dead-lettering increments kafka.dead.lettered -> KafkaMessageDeadLettered alert (Walk 40).
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
