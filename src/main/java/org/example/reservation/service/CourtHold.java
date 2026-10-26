package org.example.reservation.service;

import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proof that the caller's transaction holds the court exclusively (REQ-04 /
 * REQ-15). Only {@link CourtAllocation#hold} creates one (ArchUnit rule in
 * {@code C03ArchitectureRuleTest}), and every allocating operation of
 * {@link CourtAllocation} takes one as a parameter - so an allocation cannot be
 * requested without the hold (C03 cross-view finding X-5).
 *
 * The proof is valid only in the transaction that took the lock (C05 DD
 * precondition, C06-F1): the hold registers a marker with that transaction's
 * synchronizations, and every use checks that the marker belongs to the
 * transaction running now. A transaction that has ended, one that is suspended
 * (an inner REQUIRES_NEW), or one on another thread has a different list.
 */
public final class CourtHold {

    private final Court court;

    /** Registered with the owning transaction only; does nothing on completion. */
    private final TransactionSynchronization owningTransaction = new TransactionSynchronization() { };

    CourtHold(Court court) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A court hold needs the caller's transaction.");
        }
        this.court = court;
        TransactionSynchronizationManager.registerSynchronization(owningTransaction);
    }

    public Court court() {
        return court;
    }

    /** The hold may be used for this reservation: same transaction, same court. */
    void requireValidFor(Reservation reservation) {
        requireCurrentTransaction();
        requireCovers(reservation);
    }

    /** The lock lives until the owning transaction ends; outside it the hold proves nothing. */
    private void requireCurrentTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(owningTransaction)) {
            throw new IllegalStateException("Hold on court " + court.getId()
                    + " was taken in another transaction; the court lock it proves is not held here.");
        }
    }

    /** A hold on one court says nothing about another court's reservations. */
    private void requireCovers(Reservation reservation) {
        if (!court.getId().equals(reservation.getCourt().getId())) {
            throw new IllegalArgumentException("Hold on court " + court.getId()
                    + " does not cover reservation " + reservation.getId()
                    + " of court " + reservation.getCourt().getId() + ".");
        }
    }
}
