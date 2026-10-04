package com.shopflow.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = OrderEvent.TOPIC)
@ActiveProfiles("test")
class OutboxCleanupTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay relay;

    @Test
    void deletesOnlyPublishedEventsOlderThanRetention() {
        var published = new OutboxEvent("Order", "ref-1", "OrderConfirmed", "{}");
        published.markPublished();
        var pending = new OutboxEvent("Order", "ref-2", "OrderConfirmed", "{}");
        outboxRepository.saveAll(List.of(published, pending));

        int deleted = relay.cleanupPublishedBefore(Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(outboxRepository.existsById(published.getId())).isFalse();
        assertThat(outboxRepository.existsById(pending.getId())).as("unpublished rows are never deleted").isTrue();
    }
}
