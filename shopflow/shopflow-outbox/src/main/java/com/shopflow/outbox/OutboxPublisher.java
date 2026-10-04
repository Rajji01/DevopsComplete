package com.shopflow.outbox;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes an event into the outbox. Must be called inside the caller's database transaction
 * (that is the whole point), so it is a plain repository write with no transaction of its own.
 */
@Component
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, String aggregateType, String aggregateId, String eventType, Object event) {
        outboxRepository.save(new OutboxEvent(topic, aggregateType, aggregateId, eventType, toJson(event)));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + event, e);
        }
    }
}
