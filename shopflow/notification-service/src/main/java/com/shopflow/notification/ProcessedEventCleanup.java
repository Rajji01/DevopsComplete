package com.shopflow.notification;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** De-duplication bookkeeping only needs to outlive Kafka's retention; after that it is dead weight. */
@Component
public class ProcessedEventCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanup.class);

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public ProcessedEventCleanup(ProcessedEventRepository repository,
                                 @Value("${processed-events.retention:7d}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    @Scheduled(cron = "${processed-events.cleanup-cron:0 50 3 * * *}")
    @Transactional
    public void run() {
        int deleted = repository.deleteProcessedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("deleted {} processed-event rows older than {}", deleted, retention);
        }
    }
}
