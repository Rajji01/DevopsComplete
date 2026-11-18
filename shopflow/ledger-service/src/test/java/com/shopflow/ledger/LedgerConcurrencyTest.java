package com.shopflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.shopflow.ledger.domain.AccountType;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;

/**
 * NOT @Transactional on purpose: the idempotency race needs real concurrent commits across threads.
 * Proves record() recovers from a concurrent double-post (Walk 99). Before the fix — record() being
 * @Transactional with the catch inside — a losing thread's commit threw UnexpectedRollbackException
 * and this test failed; after the fix (TransactionTemplate + catch outside) every thread returns the
 * one winning entry.
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerConcurrencyTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JournalEntryRepository journalEntries;

    @Test
    void concurrentRecordsWithTheSameIdempotencyKeyPostExactlyOnce() throws Exception {
        // unique account codes so this test cannot pollute the balance-asserting tests
        String cash = accounts.create("CASH-" + UUID.randomUUID(), AccountType.ASSET, "INR").getCode();
        String wallet = accounts.create("WALLET-" + UUID.randomUUID(), AccountType.LIABILITY, "INR").getCode();
        List<PostingLine> lines = List.of(
                new PostingLine(cash, PostingDirection.DEBIT, Money.of(1000, "INR")),
                new PostingLine(wallet, PostingDirection.CREDIT, Money.of(1000, "INR")));

        String key = UUID.randomUUID().toString();
        String ref = "order-" + UUID.randomUUID();
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        var entryIds = new ConcurrentLinkedQueue<Long>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();                                  // all fire at once
                    entryIds.add(ledger.record(key, ref, "INR", lines).getId()); // must NOT throw
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();   // rethrows any thread's exception (the old code failed here)
            }
        }

        assertThat(journalEntries.findByReference(ref)).hasSize(1);            // posted exactly once
        assertThat(entryIds.stream().distinct().count()).isEqualTo(1L);       // all threads got the winner
        assertThat(ledger.balanceOf(cash)).isEqualTo(Money.of(1000, "INR")); // money moved once
    }
}
