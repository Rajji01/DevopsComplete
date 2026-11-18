package com.shopflow.ledger.domain;

/**
 * A requested posting leg before it is persisted: which account (by stable code), which direction,
 * how much. The request shape, deliberately separate from the {@code Posting} entity — the API/saga
 * boundary speaks account codes and Money, the database speaks account ids and minor-unit columns.
 */
public record PostingLine(String accountCode, PostingDirection direction, Money amount) {

    public PostingLine {
        if (accountCode == null || accountCode.isBlank()) {
            throw new IllegalArgumentException("accountCode required");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction required");
        }
        if (amount == null || !amount.isPositive()) {
            // amounts are stored positive; the direction carries the sign
            throw new IllegalArgumentException("posting amount must be positive");
        }
    }
}
