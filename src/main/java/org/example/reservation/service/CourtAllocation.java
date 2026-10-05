package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Court Allocation - C03 ADR-6: the single path into CONFIRMED.
 *
 * Owns the BR-02 invariant, the per-court exclusive hold (REQ-04 / REQ-15) and
 * the guards that protect an allocation (BR-07, BR-04, BR-02). OP-03 Confirm and
 * OP-05 Approve REQUEST an allocation here; neither performs it. This replaces
 * the AS-IS arrangement in which both operations had to remember the hold and the
 * guards themselves (driver AD-6, finding F-A2).
 *
 * The rule "only this class moves a reservation into CONFIRMED or takes the
 * court hold" is checked by {@code C03ArchitectureRuleTest}, so a future
 * allocating operation cannot silently bypass it.
 */
@Component
public class CourtAllocation {

    private final CourtRepository courtRepository;
    private final ReservationRepository reservationRepository;

    public CourtAllocation(CourtRepository courtRepository, ReservationRepository reservationRepository) {
        this.courtRepository = courtRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * REQ-04 / REQ-15: hold the reservation's court exclusively until the caller's
     * transaction ends, so the "read overlapping, then write CONFIRMED" window of
     * any allocating operation cannot be interleaved with another one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CourtHold hold(Reservation reservation) {
        Long courtId = reservation.getCourt().getId();
        Court court = courtRepository.findByIdForUpdate(courtId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));
        return new CourtHold(court);
    }

    /**
     * The guards that protect an allocation, evaluated under the hold. OP-03 on a
     * gated court calls this without allocating, so that an impossible request
     * never reaches an approver (V-03.12); OP-05 re-evaluates them at approval
     * time through {@link #confirmApproved} (REQ-10).
     */
    public void requireAdmissible(CourtHold hold, Reservation reservation) {
        hold.requireCovers(reservation);
        Court court = hold.court();
        if (!court.isActive()) {                                                 // C5 / E8, BR-07
            throw new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
        }
        if (!court.covers(reservation.getStartTime(), reservation.getEndTime())) {  // C6 / E9, BR-04
            throw new ReservationException(ReservationErrorCode.OUTSIDE_OPENING_HOURS, String.format(
                    "Reservation must lie within opening hours %s-%s of court '%s' on a single day.",
                    court.getOpeningTime(), court.getClosingTime(), court.getName()));
        }
        if (!isFree(court.getId(), reservation.getStartTime(), reservation.getEndTime())) {
            throw new ReservationException(ReservationErrorCode.CONFLICT,         // C7 / E10, BR-02
                    "Slot overlaps an existing CONFIRMED reservation of this court.");
        }
    }

    /** OP-03 on a self-service court: DRAFT -> CONFIRMED, the court is allocated. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(CourtHold hold, Reservation reservation) {
        requireAdmissible(hold, reservation);
        reservation.confirm();
        reservationRepository.save(reservation);
    }

    /** OP-05: PENDING_APPROVAL -> CONFIRMED, the court is allocated by an approver's decision. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmApproved(CourtHold hold, Reservation reservation, AppUser approver, Instant at) {
        requireAdmissible(hold, reservation);
        reservation.approve(approver, at);
        reservationRepository.save(reservation);
    }

    /**
     * BR-02 predicate: is the slot free of every reservation in a blocking state?
     * Without a hold the answer is advisory (OP-02, assumption A-2); under a hold
     * it is the allocation decision.
     */
    @Transactional(readOnly = true)
    public boolean isFree(Long courtId, LocalDateTime start, LocalDateTime end) {
        return reservationRepository
                .findOverlapping(courtId, ReservationState.blockingStates(), start, end)
                .isEmpty();
    }
}
