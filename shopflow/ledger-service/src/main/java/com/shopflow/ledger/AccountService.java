package com.shopflow.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.AccountNotFoundException;
import com.shopflow.ledger.domain.AccountType;

/**
 * The chart of accounts. Accounts are created deliberately (not auto-conjured by a posting), so a
 * typo'd account code is a rejected posting, not a silent new account that quietly absorbs money.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Idempotent create: a repeated (or concurrent) call with the same {@code code} returns the
     * existing account — the unique index is the guarantee, the pre-check is only an optimization.
     *
     * Deliberately NOT @Transactional (Walk 99): each repository call is its own transaction, so a
     * concurrent insert's unique-key violation can be caught here and recovered with a fresh
     * re-fetch. A @Transactional method could not — the violation marks its transaction
     * rollback-only, and its commit would then throw UnexpectedRollbackException past this catch.
     */
    public Account create(String code, AccountType type, String currency) {
        var existing = accounts.findByCode(code);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return accounts.save(new Account(code, type, currency));
        } catch (DataIntegrityViolationException raceLost) {
            // another request created the same code between our check and insert
            log.info("account {} created concurrently, returning the winner", code);
            return accounts.findByCode(code).orElseThrow(() -> raceLost);
        }
    }

    @Transactional(readOnly = true)
    public Account require(String code) {
        return accounts.findByCode(code).orElseThrow(() -> new AccountNotFoundException(code));
    }
}
