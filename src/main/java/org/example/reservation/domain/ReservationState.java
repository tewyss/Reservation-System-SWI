package org.example.reservation.domain;

public enum ReservationState {
    /** Created but not yet approved; does not block the court. */
    DRAFT,
    /** Approved; occupies the court and participates in the overlap rule. */
    CONFIRMED,
    /** Withdrawn; frees the court and no longer blocks other reservations. */
    CANCELLED
}
