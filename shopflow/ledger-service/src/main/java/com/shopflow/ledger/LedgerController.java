package com.shopflow.ledger;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.AccountType;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;

/**
 * HTTP surface of the ledger (increment 1b). Posting a journal entry is idempotent: the same
 * {@code idempotencyKey} always returns the same entry and never double-posts, so a client (or the
 * saga consumer in increment 2) can retry freely.
 */
@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    public record CreateAccountRequest(
            @NotBlank @Size(max = 64) String code,
            @NotNull AccountType type,
            @NotBlank @Size(min = 3, max = 3) String currency) {
    }

    public record LineRequest(
            @NotBlank @Size(max = 64) String accountCode,
            @NotNull PostingDirection direction,
            @Positive long amountMinor) {
    }

    public record RecordEntryRequest(
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 64) String reference,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    public record AccountResponse(String code, AccountType type, String currency) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.getCode(), a.getType(), a.getCurrency());
        }
    }

    public record JournalEntryResponse(Long id, String idempotencyKey, String reference,
                                       JournalEntryStatus status, String currency, Instant createdAt) {
        static JournalEntryResponse from(JournalEntry e) {
            return new JournalEntryResponse(e.getId(), e.getIdempotencyKey(), e.getReference(),
                    e.getStatus(), e.getCurrency(), e.getCreatedAt());
        }
    }

    public record BalanceResponse(String accountCode, long minorUnits, String currency) {
    }

    private final LedgerService ledger;
    private final AccountService accounts;
    private final JournalEntryRepository journalEntries;

    public LedgerController(LedgerService ledger, AccountService accounts, JournalEntryRepository journalEntries) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.journalEntries = journalEntries;
    }

    /** Create (idempotently) an account in the chart of accounts. */
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accounts.create(request.code(), request.type(), request.currency()));
    }

    /** Record a balanced journal entry, exactly once per idempotency key. */
    @PostMapping("/journal-entries")
    public ResponseEntity<JournalEntryResponse> record(@Valid @RequestBody RecordEntryRequest request) {
        List<PostingLine> lines = request.lines().stream()
                .map(l -> new PostingLine(l.accountCode(), l.direction(), Money.of(l.amountMinor(), request.currency())))
                .toList();
        JournalEntry entry = ledger.record(request.idempotencyKey(), request.reference(), request.currency(), lines);
        return ResponseEntity.created(URI.create("/api/v1/journal-entries/" + entry.getId()))
                .body(JournalEntryResponse.from(entry));
    }

    /** An account's balance, derived from its postings. */
    @GetMapping("/accounts/{code}/balance")
    public BalanceResponse balance(@PathVariable String code) {
        Money b = ledger.balanceOf(code);
        return new BalanceResponse(code, b.minorUnits(), b.currency());
    }

    /** All entries for a business reference (orderRef / trade id) — audit + reconciliation view. */
    @GetMapping("/journal-entries")
    public List<JournalEntryResponse> byReference(@RequestParam String reference) {
        return journalEntries.findByReference(reference).stream()
                .map(JournalEntryResponse::from)
                .toList();
    }
}
