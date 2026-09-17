package org.example.reservation.domain;

/**
 * Lifecycle states of a {@link Reservation}.
 *
 * <pre>
 *   DRAFT --confirm--> CONFIRMED --cancel--> CANCELLED
 *     \-----------------cancel-------------------/
 * </pre>
 */
public enum ReservationState {
    /** Created but not yet approved; does not block the court. */
    DRAFT,
    /** Approved; occupies the court and participates in the overlap rule. */
    CONFIRMED,
    /** Withdrawn; frees the court and no longer blocks other reservations. */
    CANCELLED
}
