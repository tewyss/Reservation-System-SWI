package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reservation Lifecycle - the member-facing operations of Specification
 * Baseline v0.2 (C03 static architecture G2).
 *
 * Every guard below names the requirement or business rule it realises; the
 * authoritative text is {@code docs/c02-baseline-v0.1.md} and
 * {@code docs/c02-change-v0.2-approval.md}.
 *
 * <ul>
 *   <li>OP-01 {@link #create} - REQ-01, REQ-07</li>
 *   <li>OP-02 {@link #checkAvailability} - REQ-02, REQ-13</li>
 *   <li>OP-03 {@link #confirm} - REQ-03, REQ-04, REQ-07, REQ-08, REQ-09</li>
 *   <li>OP-04 {@link #cancel} - REQ-05, REQ-06, REQ-07</li>
 * </ul>
 *
 * The approval operations OP-05..OP-07 belong to {@link ApprovalWorkflow}, and
 * the transition into CONFIRMED belongs to {@link CourtAllocation} (C03 ADR-6):
 * this class decides the DRAFT and CANCELLED edges and REQUESTS the others.
 * See {@code docs/c03-architecture.md}, statechart ownership table G3.
 */
@Service
public class ReservationService {

    /** BR-03.1 (amended by v0.2): the cancellable source states. */
    private static final Set<ReservationState> CANCELLABLE_FROM = EnumSet.of(
            ReservationState.DRAFT, ReservationState.PENDING_APPROVAL, ReservationState.CONFIRMED);

    private final ReservationRepository reservationRepository;
    private final CourtRepository courtRepository;
    private final AppUserRepository userRepository;
    private final CourtAllocation courtAllocation;
    private final ApprovalWorkflow approvalWorkflow;
    private final NotificationDispatcher notifications;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              CourtRepository courtRepository,
                              AppUserRepository userRepository,
                              CourtAllocation courtAllocation,
                              ApprovalWorkflow approvalWorkflow,
                              NotificationDispatcher notifications,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.courtRepository = courtRepository;
        this.userRepository = userRepository;
        this.courtAllocation = courtAllocation;
        this.approvalWorkflow = approvalWorkflow;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ==================================================================
    // OP-01 Create Reservation
    // ==================================================================

    /**
     * OP-01 / REQ-01: create a DRAFT reservation owned by the actor.
     *
     * Deliberately does NOT check overlap or opening hours: create is a claim, not
     * an allocation (consistency finding C-1). Unchanged by the v0.2 approval
     * change - see impact analysis section 2.2.
     */
    @Transactional
    public Reservation create(Long actorId, Long courtId, LocalDateTime start, LocalDateTime end) {
        AppUser actor = requireActor(actorId);                      // BR-05  (A1)
        Court court = requireActiveCourt(courtId);                  // BR-07  (A2, A3)
        requireValidInterval(start, end);                           // BR-01  (A4, A5)

        Reservation reservation = new Reservation(court, actor, start, end, clock.instant());
        return reservationRepository.save(reservation);
    }

    // ==================================================================
    // OP-02 Check Availability
    // ==================================================================

    /**
     * OP-02 / REQ-02 + REQ-13: is {@code [start, end)} bookable on this court now,
     * and what should the member know about approval?
     *
     * Pure query - no state change. {@code available} counts CONFIRMED
     * reservations only (BR-02, unchanged by v0.2); the approval fields are
     * disclosure, because under change decision D-2 a pending request does not
     * block the slot.
     */
    @Transactional(readOnly = true)
    public AvailabilityResult checkAvailability(Long courtId, LocalDateTime start, LocalDateTime end) {
        Court court = courtRepository.findById(courtId)             // B2: an error, not "unavailable"
                .orElseThrow(() -> new ReservationException(
                        ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));

        boolean gated = court.isRequiresApproval();

        if (!start.isBefore(end)) {                                                     // B1 / BR-01
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.INVALID_INTERVAL, gated, 0);
        }

        int pending = approvalWorkflow.livePendingCount(courtId, start, end);           // REQ-13

        if (!court.isActive()) {                                                        // B3 / BR-07
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.COURT_INACTIVE, gated, pending);
        }
        if (!court.covers(start, end)) {                                                // B4 / BR-04
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS, gated, pending);
        }
        if (!courtAllocation.isFree(courtId, start, end)) {                             // B5 / BR-02
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.CONFLICT, gated, pending);
        }
        return AvailabilityResult.free(gated, pending);
    }

    // ==================================================================
    // OP-03 Confirm (submit) Reservation
    // ==================================================================

    /**
     * OP-03 / REQ-03: submit a DRAFT reservation.
     *
     * REQ-09 (v0.2): the guards decide whether the request is ADMISSIBLE; the
     * court's {@code requiresApproval} flag decides the resulting state.
     * <ul>
     *   <li>self-service court -&gt; CONFIRMED, the court is allocated;</li>
     *   <li>gated court -&gt; PENDING_APPROVAL with a BR-10 deadline, nothing allocated.</li>
     * </ul>
     *
     * C03 ADR-6: this method routes the request; it does not allocate. The
     * allocation decision (the hold and the BR-07/BR-04/BR-02 guards) belongs to
     * {@link CourtAllocation}, and entering PENDING_APPROVAL to
     * {@link ApprovalWorkflow}.
     */
    @Transactional
    public Reservation confirm(Long actorId, Long reservationId) {
        AppUser actor = requireActor(actorId);                      // BR-05
        Reservation reservation = requireReservation(reservationId);// C1
        requireAuthorizedFor(actor, reservation);                   // BR-05 (C4)

        CourtHold hold = courtAllocation.hold(reservation);         // REQ-04 / REQ-15

        requireState(reservation, ReservationState.DRAFT,           // C2, C3
                "Only a DRAFT reservation can be confirmed");

        if (hold.court().isRequiresApproval()) {                    // BR-08 / REQ-09
            courtAllocation.requireAdmissible(hold, reservation);   // C5-C7: refused up front (V-03.12)
            approvalWorkflow.submit(reservation);
            return reservation;
        }

        courtAllocation.confirm(hold, reservation);                 // C5-C7, then DRAFT -> CONFIRMED
        notifications.confirmed(reservation);                       // C9 - after commit, must not undo it
        return reservation;
    }

    // ==================================================================
    // OP-04 Cancel Reservation
    // ==================================================================

    /**
     * OP-04 / REQ-05: cancel a DRAFT, PENDING_APPROVAL or CONFIRMED reservation
     * while {@code now < start}, releasing the court.
     *
     * REQ-06: a cancel on an already CANCELLED reservation is an idempotent
     * success - it returns CANCELLED, writes nothing, and deliberately skips the
     * time boundary. D7: REJECTED and EXPIRED are NOT treated that way - they are
     * someone else's recorded outcome and must not be overwritten.
     *
     * Takes no court hold - a cancellation allocates nothing. A concurrent
     * approval of the same request is decided by the row version (C03 ADR-7,
     * V-04.11): exactly one of the two commits.
     */
    @Transactional
    public Reservation cancel(Long actorId, Long reservationId) {
        AppUser actor = requireActor(actorId);                      // BR-05
        Reservation reservation = requireReservation(reservationId);// D1
        requireAuthorizedFor(actor, reservation);                   // BR-05 (D2)

        if (reservation.getState() == ReservationState.CANCELLED) { // D3 / REQ-06
            return reservation;                                     // no write, no time check
        }
        if (!CANCELLABLE_FROM.contains(reservation.getState())) {   // D7 / BR-03.1
            throw new ReservationException(ReservationErrorCode.INVALID_STATE,
                    "A reservation in state " + reservation.getState() + " cannot be cancelled.");
        }

        LocalDateTime now = LocalDateTime.now(clock);               // BR-06, read once
        if (!now.isBefore(reservation.getStartTime())) {            // D4, D5 / BR-03.2 (strict)
            throw new ReservationException(ReservationErrorCode.TOO_LATE_TO_CANCEL,
                    "Cancellation is only allowed before the reservation starts ("
                            + reservation.getStartTime() + "); now is " + now + ".");
        }

        reservation.cancel(clock.instant());                        // BR-03.4 - record retained
        return reservationRepository.save(reservation);
    }

    // ==================================================================
    // Shared guards
    // ==================================================================

    private AppUser requireActor(Long actorId) {
        if (actorId == null) {
            throw new ReservationException(ReservationErrorCode.UNAUTHORIZED, "No actor supplied.");
        }
        return userRepository.findById(actorId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.UNAUTHORIZED, "Unknown actor: " + actorId));
    }

    /** BR-05: a MEMBER may act only on reservations they own; STAFF may act on any. */
    private void requireAuthorizedFor(AppUser actor, Reservation reservation) {
        if (actor.getRole() == AppUser.Role.STAFF) {
            return;
        }
        if (!actor.getId().equals(reservation.getUser().getId())) {
            throw new ReservationException(ReservationErrorCode.UNAUTHORIZED,
                    "Actor " + actor.getId() + " does not own reservation " + reservation.getId() + ".");
        }
    }

    private void requireState(Reservation reservation, ReservationState expected, String message) {
        if (reservation.getState() != expected) {
            throw new ReservationException(ReservationErrorCode.INVALID_STATE,
                    message + "; state was " + reservation.getState() + ".");
        }
    }

    private Court requireActiveCourt(Long courtId) {
        Court court = courtRepository.findById(courtId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));
        if (!court.isActive()) {
            throw new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
        }
        return court;
    }

    private Reservation requireReservation(Long reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.NOT_FOUND, "Reservation not found: " + reservationId));
    }

    private void requireValidInterval(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ReservationException(ReservationErrorCode.INVALID_INTERVAL,
                    "Reservation start must be strictly before end (half-open interval [start,end)).");
        }
    }
}
