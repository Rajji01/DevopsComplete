package com.shopflow.order.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Polls the outbox and publishes to Kafka (the "polling publisher" variant; Debezium CDC is the
 * other). Delivery is at-least-once: if the pod dies between send() and markPublished(), the
 * row is sent again, so consumers must be idempotent (they are: see notification-service).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration retention;

    public OutboxRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                       MeterRegistry meterRegistry, @Value("${outbox.cleanup.retention:7d}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.retention = retention;
        // backlog gauge: alert if it keeps growing (Kafka down, relay stuck)
        meterRegistry.gauge("outbox.unpublished", outboxRepository, OutboxRepository::countByPublishedAtIsNull);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.delay:1s}")
    @Transactional // holds the SKIP LOCKED rows until the batch is marked published
    public void publishPending() {
        var batch = outboxRepository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(OrderEvent.TOPIC, event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS); // wait for the broker ack, otherwise we'd mark lost messages as published
                event.markPublished();
            } catch (Exception e) {
                log.warn("could not publish outbox event {} ({}), will retry: {}", event.getId(), event.getEventType(), e.getMessage());
                return; // keep ordering: stop at the first failure, next run retries from here
            }
        }
        if (!batch.isEmpty()) {
            log.info("published {} outbox event(s)", batch.size());
        }
    }

    /** Published rows are only kept for debugging; without this the table grows forever. */
    @Scheduled(cron = "${outbox.cleanup.cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        cleanupPublishedBefore(Instant.now().minus(retention));
    }

    @Transactional
    public int cleanupPublishedBefore(Instant before) {
        int deleted = outboxRepository.deletePublishedBefore(before);
        if (deleted > 0) {
            log.info("deleted {} published outbox event(s) older than {}", deleted, before);
        }
        return deleted;
    }
}
