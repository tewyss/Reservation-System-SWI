package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The four core operations of Specification Baseline v0.1.
 *
 * Every guard below names the requirement or business rule it realises; the
 * authoritative text is {@code docs/c02-baseline-v0.1.md}.
 *
 * <ul>
 *   <li>OP-01 {@link #create} - REQ-01, REQ-07</li>
 *   <li>OP-02 {@link #checkAvailability} - REQ-02</li>
 *   <li>OP-03 {@link #confirm} - REQ-03, REQ-04, REQ-07, REQ-08</li>
 *   <li>OP-04 {@link #cancel} - REQ-05, REQ-06, REQ-07</li>
 * </ul>
 *
 * C03 will decide how this behaviour should be structured. C02 deliberately
 * keeps it in one place.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final CourtRepository courtRepository;
    private final AppUserRepository userRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              CourtRepository courtRepository,
                              AppUserRepository userRepository,
                              NotificationService notificationService,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.courtRepository = courtRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // OP-01 Create Reservation
    // ------------------------------------------------------------------

    /**
     * OP-01 / REQ-01: create a DRAFT reservation owned by the actor.
     *
     * Deliberately does NOT check overlap or opening hours: create is a claim,
     * not an allocation (consistency finding C-1). Availability is unchanged by
     * a successful create.
     */
    @Transactional
    public Reservation create(Long actorId, Long courtId, LocalDateTime start, LocalDateTime end) {
        AppUser actor = requireActor(actorId);                      // BR-05  (A1)
        Court court = requireActiveCourt(courtId);                  // BR-07  (A2, A3)
        requireValidInterval(start, end);                           // BR-01  (A4, A5)

        Reservation reservation = new Reservation(court, actor, start, end, clock.instant());
        return reservationRepository.save(reservation);
    }

    // ------------------------------------------------------------------
    // OP-02 Check Availability
    // ------------------------------------------------------------------

    /**
     * OP-02 / REQ-02: is {@code [start, end)} bookable on this court right now?
     *
     * Pure query - no state change. Evaluates exactly the guards OP-03 enforces
     * (interval, court active, opening hours, BR-02 overlap), so an AVAILABLE
     * answer cannot be contradicted by an immediately following confirm
     * (consistency findings C-2 and C-3).
     */
    @Transactional(readOnly = true)
    public AvailabilityResult checkAvailability(Long courtId, LocalDateTime start, LocalDateTime end) {
        Court court = courtRepository.findById(courtId)             // B2: an error, not "unavailable"
                .orElseThrow(() -> new ReservationException(
                        ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));

        if (!start.isBefore(end)) {                                                     // B1 / BR-01
            return AvailabilityResult.blocked(AvailabilityResult.Reason.INVALID_INTERVAL);
        }
        if (!court.isActive()) {                                                        // B3 / BR-07
            return AvailabilityResult.blocked(AvailabilityResult.Reason.COURT_INACTIVE);
        }
        if (!court.covers(start, end)) {                                                // B4 / BR-04
            return AvailabilityResult.blocked(AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS);
        }
        if (!isSlotFree(courtId, start, end)) {                                         // B5 / BR-02
            return AvailabilityResult.blocked(AvailabilityResult.Reason.CONFLICT);
        }
        return AvailabilityResult.free();
    }

    // ------------------------------------------------------------------
    // OP-03 Confirm Reservation
    // ------------------------------------------------------------------

    /**
     * OP-03 / REQ-03: move a DRAFT reservation to CONFIRMED, allocating the court.
     *
     * REQ-04: the check-then-act sequence (read overlapping CONFIRMED, then write)
     * is serialised per court by an exclusive hold on the court row, so two
     * conflicting confirms cannot both pass the check. Losers are rejected with
     * CONFLICT and stay DRAFT.
     */
    @Transactional
    public Reservation confirm(Long actorId, Long reservationId) {
        AppUser actor = requireActor(actorId);                      // BR-05
        Reservation reservation = requireReservation(reservationId);// C1
        requireAuthorizedFor(actor, reservation);                   // BR-05 (C4)

        // REQ-04: exclusive hold for the whole check-then-act window.
        Court court = lockCourt(reservation.getCourt().getId());

        if (reservation.getState() != ReservationState.DRAFT) {     // C2, C3
            throw new ReservationException(ReservationErrorCode.INVALID_STATE,
                    "Only a DRAFT reservation can be confirmed; state was " + reservation.getState());
        }
        if (!court.isActive()) {                                    // C5 / BR-07
            throw new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
        }
        if (!court.covers(reservation.getStartTime(), reservation.getEndTime())) {   // C6 / BR-04
            throw new ReservationException(ReservationErrorCode.OUTSIDE_OPENING_HOURS, String.format(
                    "Reservation must lie within opening hours %s-%s of court '%s' on a single day.",
                    court.getOpeningTime(), court.getClosingTime(), court.getName()));
        }
        if (!isSlotFree(court.getId(), reservation.getStartTime(), reservation.getEndTime())) { // C7 / BR-02
            throw new ReservationException(ReservationErrorCode.CONFLICT,
                    "Slot overlaps an existing CONFIRMED reservation of this court.");
        }

        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);
        notifyOwner(saved);                                         // C9 - failure must not undo it
        return saved;
    }

    // ------------------------------------------------------------------
    // OP-04 Cancel Reservation
    // ------------------------------------------------------------------

    /**
     * OP-04 / REQ-05: cancel a DRAFT or CONFIRMED reservation while
     * {@code now < start}, releasing the court.
     *
     * REQ-06: a cancel on an already CANCELLED reservation is an idempotent
     * success - it returns CANCELLED, writes nothing, and deliberately skips the
     * time boundary (a reservation validly cancelled at 09:00 must still answer
     * CANCELLED when a retry lands at 10:05).
     */
    @Transactional
    public Reservation cancel(Long actorId, Long reservationId) {
        AppUser actor = requireActor(actorId);                      // BR-05
        Reservation reservation = requireReservation(reservationId);// D1
        requireAuthorizedFor(actor, reservation);                   // BR-05 (D2)

        if (reservation.getState() == ReservationState.CANCELLED) { // D3 / REQ-06
            return reservation;                                     // no write, no time check
        }
        if (!BR03_CANCELLABLE_FROM.contains(reservation.getState())) {
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

    // ------------------------------------------------------------------
    // Shared guards
    // ------------------------------------------------------------------

    /** BR-03.1: the cancellable source states. */
    private static final java.util.Set<ReservationState> BR03_CANCELLABLE_FROM =
            java.util.EnumSet.of(ReservationState.DRAFT, ReservationState.CONFIRMED);

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

    private Court requireActiveCourt(Long courtId) {
        Court court = courtRepository.findById(courtId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));
        if (!court.isActive()) {
            throw new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
        }
        return court;
    }

    private Court lockCourt(Long courtId) {
        return courtRepository.findByIdForUpdate(courtId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId));
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

    /** BR-02: is the slot free of every reservation in a blocking state? */
    private boolean isSlotFree(Long courtId, LocalDateTime start, LocalDateTime end) {
        return reservationRepository
                .findOverlapping(courtId, ReservationState.blockingStates(), start, end)
                .isEmpty();
    }

    /** C9 / C01 ADR-3: a notification failure must not undo a valid confirmation. */
    private void notifyOwner(Reservation reservation) {
        try {
            notificationService.notifyConfirmed(reservation);
        } catch (NotificationException e) {
            log.warn("Notification failed for reservation {}: {}", reservation.getId(), e.getMessage());
        }
    }
}
