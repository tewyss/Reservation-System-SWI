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
     * C05 DD: the admissibility decision table - evaluated top-down, the first
     * failing rule wins. It is the ONE place the rules live: OP-02 asks it without
     * a hold (the verdict is advisory, A-2), {@link #requireAdmissible} asks it
     * under the hold (the verdict is the allocation decision), so availability and
     * confirm cannot disagree on the same slot (C02 M-1, C03 C-14). The only rule
     * that needs the database is evaluated last.
     */
    @Transactional(readOnly = true)
    public AvailabilityResult.Reason assess(Court court, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {             // B1, BR-01
            return AvailabilityResult.Reason.INVALID_INTERVAL;
        }
        if (!court.isActive()) {                                                 // B3 / C5 / E8, BR-07
            return AvailabilityResult.Reason.COURT_INACTIVE;
        }
        if (!court.covers(start, end)) {                                         // B4 / C6 / E9, BR-04
            return AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS;
        }
        if (!isFree(court.getId(), start, end)) {                                // B5 / C7 / E10, BR-02
            return AvailabilityResult.Reason.CONFLICT;
        }
        return AvailabilityResult.Reason.AVAILABLE;
    }

    /**
     * The guards that protect an allocation, evaluated under the hold. OP-03 on a
     * gated court calls this without allocating, so that an impossible request
     * never reaches an approver (V-03.12); OP-05 re-evaluates them at approval
     * time through {@link #confirmApproved} (REQ-10).
     *
     * A switch EXPRESSION, so a new verdict that has no failure code yet is a
     * compile error rather than a silent "admissible".
     */
    public void requireAdmissible(CourtHold hold, Reservation reservation) {
        hold.requireValidFor(reservation);
        Court court = hold.court();
        ReservationException refusal = switch (assess(court, reservation.getStartTime(), reservation.getEndTime())) {
            case AVAILABLE -> null;
            case INVALID_INTERVAL -> new ReservationException(ReservationErrorCode.INVALID_INTERVAL,
                    "Reservation start must be strictly before end (half-open interval [start,end)).");
            case COURT_INACTIVE -> new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
            case OUTSIDE_OPENING_HOURS -> new ReservationException(ReservationErrorCode.OUTSIDE_OPENING_HOURS,
                    String.format("Reservation must lie within opening hours %s-%s of court '%s' on a single day.",
                            court.getOpeningTime(), court.getClosingTime(), court.getName()));
            case CONFLICT -> new ReservationException(ReservationErrorCode.CONFLICT,
                    "Slot overlaps an existing CONFIRMED reservation of this court.");
        };
        if (refusal != null) {
            throw refusal;
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
