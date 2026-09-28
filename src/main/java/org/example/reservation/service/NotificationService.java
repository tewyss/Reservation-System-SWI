package org.example.reservation.service;

import org.example.reservation.domain.Reservation;

/**
 * EXTERNAL / SYSTEM BOUNDARY.
 *
 * The reservation system does not send messages itself; it delegates to a
 * Notification Service dependency. This interface is the seam that lets us swap a
 * real provider (email/SMS/push) for a stub in tests, and lets us handle the
 * boundary failing (timeout/error) without breaking reservations.
 *
 * The v0.2 approval change adds more OCCASIONS to notify (a decision is pending,
 * a decision was made, a request expired) but does not change the boundary's
 * failure semantics: a notification failure must never undo a committed state
 * change (C01 ADR-3).
 */
public interface NotificationService {

    /**
     * Notify the reservation's owner that it has been confirmed.
     *
     * @throws NotificationException if the boundary is unreachable / times out
     */
    void notifyConfirmed(Reservation reservation) throws NotificationException;

    /** OP-03 / REQ-09: a request on a gated court now awaits an approver's decision. */
    void notifyApprovalPending(Reservation reservation) throws NotificationException;

    /** OP-05 / OP-06 / OP-07: tell the owner the outcome (approved, rejected or expired). */
    void notifyDecision(Reservation reservation) throws NotificationException;
}
