package com.shopflow.order.payment;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * De-duplication bookkeeping must outlive the OLDEST record the topic can still deliver — which is
 * NOT the 7d retention (Walk 59): a segment is deleted only when its newest record passes retention,
 * so on a quiet partition a record survives up to retention + segment-roll (7d + 7d default), and
 * auto-offset-reset=earliest means a reset/expired group replays all of it. Hence the 14d default.
 * (The payment listener is also status-guarded, but the dedupe table is the first line.)
 */
@Component
public class ProcessedEventCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanup.class);

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public ProcessedEventCleanup(ProcessedEventRepository repository,
                                 @Value("${processed-events.retention:14d}") Duration retention) {
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
