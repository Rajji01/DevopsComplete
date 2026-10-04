package com.shopflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"orders.events", "orders.events.DLT", PaymentEvent.TOPIC})
@ActiveProfiles("test")
class PaymentServiceApplicationTests {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker kafka;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private MeterRegistry meterRegistry;

    private Consumer<String, String> paymentEvents;

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps("test-" + UUID.randomUUID(), "true", kafka));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        paymentEvents = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        kafka.consumeFromAnEmbeddedTopic(paymentEvents, PaymentEvent.TOPIC);
    }

    @AfterEach
    void close() {
        paymentEvents.close();
    }

    private static String orderConfirmed(UUID eventId, String orderRef, String sku, int quantity) {
        return """
                {"eventId":"%s","type":"OrderConfirmed","orderRef":"%s","sku":"%s","quantity":%d,
                 "status":"CONFIRMED","occurredAt":"2026-10-04T10:00:00Z"}""".formatted(eventId, orderRef, sku, quantity);
    }

    private String awaitPaymentEvent(String orderRef) {
        var found = new String[1];
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (var r : KafkaTestUtils.getRecords(paymentEvents, Duration.ofMillis(500))) {
                if (orderRef.equals(r.key())) {
                    found[0] = r.value();
                }
            }
            assertThat(found[0]).isNotNull();
        });
        return found[0];
    }

    @Test
    void capturesPaymentForAConfirmedOrderAndPublishesIt() {
        String orderRef = UUID.randomUUID().toString();
        kafkaTemplate.send("orders.events", orderRef, orderConfirmed(UUID.randomUUID(), orderRef, "AIRPODS-PRO", 2));

        String event = awaitPaymentEvent(orderRef);
        assertThat(event).contains("\"type\":\"PaymentCaptured\"").contains("\"amount\":49800.00");
    }

    @Test
    void declinesAboveTheCardLimitAndPublishesFailure() {
        String orderRef = UUID.randomUUID().toString();
        kafkaTemplate.send("orders.events", orderRef, orderConfirmed(UUID.randomUUID(), orderRef, "IPHONE-15", 3)); // 239,700 > 100,000

        String event = awaitPaymentEvent(orderRef);
        assertThat(event).contains("\"type\":\"PaymentFailed\"").contains("card limit exceeded");
    }

    @Test
    void redeliveredEventChargesOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        String orderRef = UUID.randomUUID().toString();
        String payload = orderConfirmed(eventId, orderRef, "PS5-SLIM", 1);
        double capturedBefore = meterRegistry.counter("payments", "status", "CAPTURED").count();
        double duplicatesBefore = meterRegistry.counter("payments.duplicates").count();
        kafkaTemplate.send("orders.events", orderRef, payload);
        kafkaTemplate.send("orders.events", orderRef, payload);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(meterRegistry.counter("payments.duplicates").count()).isEqualTo(duplicatesBefore + 1));
        assertThat(payments.findById(eventId)).isPresent();
        // the meter registry is shared by every test in this class, so compare with the value before
        assertThat(meterRegistry.counter("payments", "status", "CAPTURED").count()).isEqualTo(capturedBefore + 1);
    }

    @Test
    void ignoresOtherOrderEvents() {
        String orderRef = UUID.randomUUID().toString();
        kafkaTemplate.send("orders.events", orderRef, orderConfirmed(UUID.randomUUID(), orderRef, "PS5-SLIM", 1)
                .replace("OrderConfirmed", "OrderRejected"));

        // give the listener time, then make sure nothing was charged
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(payments.findAll()).noneMatch(p -> p.getOrderRef().equals(orderRef)));
    }
}
