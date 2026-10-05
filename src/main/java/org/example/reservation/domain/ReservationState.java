package org.example.reservation.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reservation lifecycle states of Specification Baseline v0.2
 * (see docs/c02-change-v0.2-approval.md section 7b).
 *
 * The {@code blocksCourt} flag is the single source of truth for the BR-02
 * blocking set. Note that PENDING_APPROVAL does NOT block - change decision D-2:
 * an undecided request must not deny the court to everyone else for the whole
 * approval window.
 */
public enum ReservationState {

    /** Created but not submitted; does NOT block the court. */
    DRAFT(false),

    /** Submitted on an approval-gated court and awaiting a decision; does NOT block (D-2). */
    PENDING_APPROVAL(false),

    /** Allocated; blocks the court and participates in the BR-02 invariant. */
    CONFIRMED(true),

    /** Withdrawn by an authorized actor; the record is kept (BR-03.4), does not block. */
    CANCELLED(false),

    /** Terminal: an approver refused the request (BR-11). Does not block. */
    REJECTED(false),

    /** Terminal: nobody decided before the approval deadline (BR-10, BR-11). Does not block. */
    EXPIRED(false);

    private final boolean blocksCourt;

    ReservationState(boolean blocksCourt) {
        this.blocksCourt = blocksCourt;
    }

    /**
     * BR-02: exactly the states that prevent another reservation from being
     * confirmed over the same interval. Defined once, here, so that the
     * availability query (OP-02), the confirm guard (OP-03) and the approve
     * guard (OP-05) cannot drift apart - consistency checks C-2 and C-14.
     */
    public boolean blocksCourt() {
        return blocksCourt;
    }

    /** BR-11: no transition leaves a terminal state. */
    public boolean isTerminal() {
        return this == CANCELLED || this == REJECTED || this == EXPIRED;
    }

    /**
     * The edges of the v0.2 lifecycle statechart (c02-change-v0.2-approval.md
     * section 7b), encoded once (C03 ADR-7, responsibility R5). Which operation may
     * REQUEST an edge, and which element DECIDES it, is in docs/c03-architecture.md
     * G3; this table only says whether the edge exists at all.
     *
     * The CANCELLED self-loop of REQ-06 is not an edge here: a repeated cancel is
     * a no-op that never writes.
     */
    public boolean canTransitionTo(ReservationState target) {
        return switch (this) {
            case DRAFT -> target == CONFIRMED || target == PENDING_APPROVAL || target == CANCELLED;
            case PENDING_APPROVAL -> target == CONFIRMED || target == REJECTED
                    || target == EXPIRED || target == CANCELLED;
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED, REJECTED, EXPIRED -> false;
        };
    }

    /** The single source of truth for the BR-02 blocking set. */
    public static Set<ReservationState> blockingStates() {
        Set<ReservationState> blocking = EnumSet.noneOf(ReservationState.class);
        Arrays.stream(values()).filter(ReservationState::blocksCourt).forEach(blocking::add);
        return Collections.unmodifiableSet(blocking);
    }
}
