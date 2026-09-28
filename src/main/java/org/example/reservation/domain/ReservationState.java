package org.example.reservation.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reservation lifecycle states of Specification Baseline v0.1
 * (see docs/c02-baseline-v0.1.md section 8b).
 */
public enum ReservationState {

    /** Created but not allocated; does NOT block the court (BR-02). */
    DRAFT(false),

    /** Allocated; blocks the court and participates in the BR-02 invariant. */
    CONFIRMED(true),

    /** Withdrawn; the record is kept (BR-03.4) but no longer blocks the court. */
    CANCELLED(false);

    private final boolean blocksCourt;

    ReservationState(boolean blocksCourt) {
        this.blocksCourt = blocksCourt;
    }

    /**
     * BR-02: exactly the states that prevent another reservation from being
     * confirmed over the same interval. Defined once, here, so that the
     * availability query (OP-02) and the confirm guard (OP-03) cannot drift
     * apart - consistency check C-2.
     */
    public boolean blocksCourt() {
        return blocksCourt;
    }

    /** The single source of truth for the BR-02 blocking set (consistency check C-2). */
    public static Set<ReservationState> blockingStates() {
        Set<ReservationState> blocking = EnumSet.noneOf(ReservationState.class);
        Arrays.stream(values()).filter(ReservationState::blocksCourt).forEach(blocking::add);
        return Collections.unmodifiableSet(blocking);
    }
}
