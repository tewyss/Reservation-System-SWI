package org.example.reservation.service;

import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;

/**
 * Proof that the caller's transaction holds the court exclusively (REQ-04 /
 * REQ-15). Only {@link CourtAllocation#hold} creates one, and every allocating
 * operation of {@link CourtAllocation} takes one as a parameter - so an
 * allocation cannot be requested without the hold (C03 cross-view finding X-5).
 */
public final class CourtHold {

    private final Court court;

    CourtHold(Court court) {
        this.court = court;
    }

    public Court court() {
        return court;
    }

    /** A hold on one court says nothing about another court's reservations. */
    void requireCovers(Reservation reservation) {
        if (!court.getId().equals(reservation.getCourt().getId())) {
            throw new IllegalArgumentException("Hold on court " + court.getId()
                    + " does not cover reservation " + reservation.getId()
                    + " of court " + reservation.getCourt().getId() + ".");
        }
    }
}
