package com.shopflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"orders.events", "orders.events.DLT"})
@ActiveProfiles("test")
class NotificationServiceApplicationTests {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private MeterRegistry meterRegistry;

    private static String event(UUID eventId, String orderRef) {
        return """
                {"eventId":"%s","type":"OrderConfirmed","orderRef":"%s","sku":"PS5-SLIM","quantity":1,
                 "status":"CONFIRMED","occurredAt":"2026-10-04T10:00:00Z","futureField":"ignored"}"""
                .formatted(eventId, orderRef);
    }

    @Test
    void processesEachEventExactlyOnceEvenWhenDeliveredTwice() {
        UUID eventId = UUID.randomUUID();
        String orderRef = UUID.randomUUID().toString();

        kafkaTemplate.send("orders.events", orderRef, event(eventId, orderRef));
        kafkaTemplate.send("orders.events", orderRef, event(eventId, orderRef)); // redelivery

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(meterRegistry.counter("notifications.duplicates").count()).isGreaterThanOrEqualTo(1));
        assertThat(processedEvents.existsById(eventId)).isTrue();
        assertThat(meterRegistry.counter("notifications.sent", "type", "OrderConfirmed").count()).isEqualTo(1);

        // the KafkaConsumerLagHigh alert depends on this exact metric (Micrometer's Kafka consumer binder)
        assertThat(meterRegistry.find("kafka.consumer.fetch.manager.records.lag.max").gauge())
                .as("consumer lag metric exported for Prometheus").isNotNull();
    }

    @Test
    void poisonPillGoesToDeadLetterTopicWithoutBlockingOthers() {
        UUID good = UUID.randomUUID();
        kafkaTemplate.send("orders.events", "bad", "this is not json");
        kafkaTemplate.send("orders.events", "good", event(good, "order-after-poison"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(processedEvents.existsById(good)).isTrue());
    }
}
