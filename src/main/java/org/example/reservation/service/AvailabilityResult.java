package org.example.reservation.service;

/**
 * OP-02 result: the boolean answer plus the reason, because "unavailable" alone
 * is not actionable (REQ-02 acceptance gate, "Observable").
 */
public record AvailabilityResult(boolean available, Reason reason) {

    public enum Reason {
        /** The slot can be confirmed right now. */
        AVAILABLE,
        /** BR-01: start >= end. */
        INVALID_INTERVAL,
        /** BR-07: the court is not taking bookings. */
        COURT_INACTIVE,
        /** BR-04: outside opening hours or spanning a calendar day. */
        OUTSIDE_OPENING_HOURS,
        /** BR-02: overlaps a CONFIRMED reservation of the same court. */
        CONFLICT
    }

    /** The slot is bookable. (Named {@code free} because {@code available()} is the accessor.) */
    public static AvailabilityResult free() {
        return new AvailabilityResult(true, Reason.AVAILABLE);
    }

    /** The slot is not bookable, for the given reason. */
    public static AvailabilityResult blocked(Reason reason) {
        return new AvailabilityResult(false, reason);
    }
}
