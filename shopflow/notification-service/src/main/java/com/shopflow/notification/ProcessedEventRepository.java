package com.shopflow.notification;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    // keep LONGER than the oldest record the topic can still replay (retention + segment roll,
    // not just retention — see ProcessedEventCleanup, Walk 59); rows are tiny, margin is cheap
    @Modifying
    @Query("delete from ProcessedEvent e where e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
