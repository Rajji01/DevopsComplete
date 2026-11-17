package com.shopflow.ledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementBalanceRepository extends JpaRepository<SettlementBalance, String> {

    /**
     * Apply {@code delta} to an account's balance in one atomic UPDATE and return how many rows changed.
     * Returning 0 (unknown account) lets the caller fail loudly instead of silently losing money — that is
     * what makes the S3 half-settlement visible as an exception rather than a quiet partial write.
     */
    @Modifying
    @Query("update SettlementBalance b set b.balanceMinor = b.balanceMinor + :delta where b.account = :account")
    int addToBalance(@Param("account") String account, @Param("delta") long delta);
}
