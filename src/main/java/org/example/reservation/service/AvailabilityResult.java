package org.example.reservation.service;

/**
 * OP-02 result.
 *
 * {@code available} is computed from CONFIRMED reservations only (BR-02,
 * unchanged by the v0.2 change). {@code approvalRequired} and
 * {@code pendingApprovalCount} are REQ-13 disclosure: under change decision D-2 a
 * pending request does not block the slot, so a member has to be told that the
 * court is gated and that others are already waiting for the same interval.
 * The count is advisory, never a guarantee (see A-2 / C-15).
 */
public record AvailabilityResult(boolean available,
                                 Reason reason,
                                 boolean approvalRequired,
                                 int pendingApprovalCount) {

    public enum Reason {
        /** The slot can be submitted right now (subject to approval if the court is gated). */
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
    public static AvailabilityResult free(boolean approvalRequired, int pendingApprovalCount) {
        return new AvailabilityResult(true, Reason.AVAILABLE, approvalRequired, pendingApprovalCount);
    }

    /** The slot is not bookable, for the given reason. */
    public static AvailabilityResult blocked(Reason reason, boolean approvalRequired,
                                             int pendingApprovalCount) {
        return new AvailabilityResult(false, reason, approvalRequired, pendingApprovalCount);
    }
}
