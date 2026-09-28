package org.example.reservation.service;

/**
 * Outcome codes named by the specification slices in
 * {@code docs/c02-baseline-v0.1.md}. Each failure outcome in a slice maps to
 * exactly one code, so a verification example can assert the outcome and not
 * just "an error happened".
 */
public enum ReservationErrorCode {
    /** BR-05: actor unknown, or a MEMBER acting on a reservation they do not own. */
    UNAUTHORIZED,
    /** OP-01 A2 / OP-02 B2: the court does not exist. */
    COURT_NOT_FOUND,
    /** BR-07: the court exists but is not taking bookings. */
    COURT_INACTIVE,
    /** BR-01: start >= end. */
    INVALID_INTERVAL,
    /** The reservation does not exist. */
    NOT_FOUND,
    /** The reservation is not in a state this operation accepts. */
    INVALID_STATE,
    /** BR-02: the interval overlaps a CONFIRMED reservation of the same court. */
    CONFLICT,
    /** BR-04: the interval leaves the court's opening hours or spans a day boundary. */
    OUTSIDE_OPENING_HOURS,
    /** BR-03.2: now >= reservation start. */
    TOO_LATE_TO_CANCEL
}
