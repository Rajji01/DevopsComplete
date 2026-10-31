package com.shopflow.outbox;

import com.shopflow.order.outbox.OrderEvent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

// Park the relay's poll at 1h so its background @Scheduled run cannot publish this test's "pending"
// row mid-test (which would then let cleanup delete it, flaking "unpublished rows are never deleted").
// This test drives cleanup directly, so it never needs the relay to fire. The distinct property also
// gives this class its own (isolated, empty) context, so the single startup poll is a harmless no-op.
@SpringBootTest(classes = com.shopflow.order.OrderServiceApplication.class)
@EmbeddedKafka(partitions = 1, topics = OrderEvent.TOPIC)
@ActiveProfiles("test")
@TestPropertySource(properties = "outbox.relay.delay=1h")
class OutboxCleanupTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay relay;

    @Test
    void deletesOnlyPublishedEventsOlderThanRetention() {
        var published = new OutboxEvent(OrderEvent.TOPIC, "Order", "ref-1", "OrderConfirmed", "{}");
        published.markPublished();
        var pending = new OutboxEvent(OrderEvent.TOPIC, "Order", "ref-2", "OrderConfirmed", "{}");
        outboxRepository.saveAll(List.of(published, pending));

        int deleted = relay.cleanupPublishedBefore(Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(outboxRepository.existsById(published.getId())).isFalse();
        assertThat(outboxRepository.existsById(pending.getId())).as("unpublished rows are never deleted").isTrue();
    }
}
