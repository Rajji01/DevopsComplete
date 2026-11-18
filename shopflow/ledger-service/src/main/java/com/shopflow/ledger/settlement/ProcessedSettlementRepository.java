package com.shopflow.ledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedSettlementRepository extends JpaRepository<ProcessedSettlement, String> {
}
