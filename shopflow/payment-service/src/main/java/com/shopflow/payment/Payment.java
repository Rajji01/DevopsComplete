package com.shopflow.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One payment per order, modelled as an explicit card lifecycle state machine (Walk 114).
 *
 * The real flow is two processor calls, not one: AUTHORIZE puts a hold on the card (the issuer's
 * funds check), CAPTURE moves the held money. We keep them distinct even though shopflow captures
 * immediately after authorizing — so a declined auth and a failed capture are different states, and
 * delayed capture (authorize now, capture on dispatch) is a one-line change instead of a reshape.
 *
 * INITIATED from the ledger's lifecycle is deliberately NOT a persisted state here: auth is
 * synchronous, so the first row we ever write is already AUTHORIZED (approved) or FAILED (declined).
 * SETTLED/RECONCILED live on the ledger side (the money-movement record), not on this operational row.
 *
 * Legal transitions ({@link Status#canMoveTo}) are enforced by {@link #transitionTo}: every mutation
 * goes through it, so a replayed or out-of-order saga event can never rewrite a terminal state
 * (refunding a FAILED payment, re-capturing a REFUNDED one) — it throws instead.
 *
 * Persistable with isNew()==true so the first save() is a plain INSERT, not merge()+SELECT (Walk 38).
 * The row is inserted once; later transitions mutate the loaded/managed entity via dirty checking, so
 * this stays correct.
 */
@Entity
@Table(name = "payment")
public class Payment implements Persistable<UUID> {

    public enum Status {
        AUTHORIZED, CAPTURED, FAILED, REFUNDED;

        private static final Set<Status> NONE = EnumSet.noneOf(Status.class);

        /** The statuses this one may move to. FAILED and REFUNDED are terminal (empty set). */
        Set<Status> next() {
            return switch (this) {
                // auth held: capture it, or record that the capture/void failed
                case AUTHORIZED -> EnumSet.of(CAPTURED, FAILED);
                // money moved: the only forward move is giving it back
                case CAPTURED -> EnumSet.of(REFUNDED);
                case FAILED, REFUNDED -> NONE;
            };
        }

        boolean canMoveTo(Status target) {
            return next().contains(target);
        }
    }

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "order_ref", nullable = false, unique = true, length = 64)
    private String orderRef;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    private Payment(UUID eventId, String orderRef, BigDecimal amount, Status status, String failureReason) {
        this.eventId = eventId;
        this.orderRef = orderRef;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
    }

    /** The issuer approved the hold. The money has NOT moved yet — capture() does that. */
    static Payment authorized(UUID eventId, String orderRef, BigDecimal amount) {
        return new Payment(eventId, orderRef, amount, Status.AUTHORIZED, null);
    }

    /** The issuer declined the authorization (or there was no price): a terminal row, nothing was held. */
    static Payment failed(UUID eventId, String orderRef, BigDecimal amount, String reason) {
        return new Payment(eventId, orderRef, amount, Status.FAILED, reason);
    }

    /** AUTHORIZED -> CAPTURED: the held funds are taken. */
    void capture() {
        transitionTo(Status.CAPTURED);
    }

    /** AUTHORIZED -> FAILED: the capture (or a void) did not go through. Records why. */
    void failCapture(String reason) {
        transitionTo(Status.FAILED);
        this.failureReason = reason;
    }

    /** CAPTURED -> REFUNDED: the captured money was returned. */
    void refund() {
        transitionTo(Status.REFUNDED);
    }

    private void transitionTo(Status target) {
        if (!status.canMoveTo(target)) {
            throw new IllegalPaymentTransitionException(status, target);
        }
        this.status = target;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
