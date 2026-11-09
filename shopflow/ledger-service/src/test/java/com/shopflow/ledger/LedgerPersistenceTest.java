package com.shopflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.domain.AccountNotFoundException;
import com.shopflow.ledger.domain.AccountType;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;
import com.shopflow.ledger.domain.UnbalancedEntryException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional   // each test rolls back, so derived balances stay isolated across tests
class LedgerPersistenceTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JournalEntryRepository journalEntries;

    @Autowired
    private PostingRepository postings;

    @BeforeEach
    void chartOfAccounts() {
        // idempotent create — safe to run before every test
        accounts.create("CASH", AccountType.ASSET, "INR");
        accounts.create("CUSTOMER_WALLET:c1", AccountType.LIABILITY, "INR");
    }

    private List<PostingLine> capture1000() {
        // customer pays 1000 paise: cash (asset) up, wallet liability we owe them down
        return List.of(
                new PostingLine("CASH", PostingDirection.DEBIT, Money.of(1000, "INR")),
                new PostingLine("CUSTOMER_WALLET:c1", PostingDirection.CREDIT, Money.of(1000, "INR")));
    }

    @Test
    void aBalancedEntryPersistsAndTheBalanceIsDerivedFromPostings() {
        String ref = "order-" + UUID.randomUUID();
        JournalEntry entry = ledger.record(UUID.randomUUID().toString(), ref, "INR", capture1000());

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.INITIATED);
        assertThat(journalEntries.findByReference(ref)).hasSize(1);
        assertThat(postings.findByJournalEntryId(entry.getId())).hasSize(2);

        // balance is the signed sum of postings, not a stored column
        assertThat(ledger.balanceOf("CASH")).isEqualTo(Money.of(1000, "INR"));
        assertThat(ledger.balanceOf("CUSTOMER_WALLET:c1")).isEqualTo(Money.of(-1000, "INR"));
    }

    @Test
    void replayingTheSameIdempotencyKeyPostsOnlyOnce() {
        String key = UUID.randomUUID().toString();
        String ref = "order-" + UUID.randomUUID();

        JournalEntry first = ledger.record(key, ref, "INR", capture1000());
        JournalEntry replay = ledger.record(key, ref, "INR", capture1000());

        assertThat(replay.getId()).isEqualTo(first.getId());          // same entry, not a new one
        assertThat(postings.findByJournalEntryId(first.getId())).hasSize(2); // posted once, not four legs
        assertThat(ledger.balanceOf("CASH")).isEqualTo(Money.of(1000, "INR"));
    }

    @Test
    void anUnbalancedEntryIsRejectedBeforeAnythingIsWritten() {
        String ref = "order-" + UUID.randomUUID();
        var lopsided = List.of(
                new PostingLine("CASH", PostingDirection.DEBIT, Money.of(1000, "INR")),
                new PostingLine("CUSTOMER_WALLET:c1", PostingDirection.CREDIT, Money.of(999, "INR")));

        // validate() throws before any write, so nothing is half-written (record() is @Transactional)
        assertThatThrownBy(() -> ledger.record(UUID.randomUUID().toString(), ref, "INR", lopsided))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void postingToAnUnknownAccountIsRejected() {
        var toNowhere = List.of(
                new PostingLine("CASH", PostingDirection.DEBIT, Money.of(500, "INR")),
                new PostingLine("DOES_NOT_EXIST", PostingDirection.CREDIT, Money.of(500, "INR")));

        assertThatThrownBy(() -> ledger.record(UUID.randomUUID().toString(), "r", "INR", toNowhere))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void anAccountOfTheWrongCurrencyIsRejected() {
        accounts.create("USD_CASH", AccountType.ASSET, "USD");
        var mismatched = List.of(
                new PostingLine("USD_CASH", PostingDirection.DEBIT, Money.of(1000, "INR")),
                new PostingLine("CUSTOMER_WALLET:c1", PostingDirection.CREDIT, Money.of(1000, "INR")));

        assertThatThrownBy(() -> ledger.record(UUID.randomUUID().toString(), "r", "INR", mismatched))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
