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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The operations of Specification Baseline v0.2.
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
 *   <li>OP-05 {@link #approve} - REQ-10, REQ-14, REQ-15</li>
 *   <li>OP-06 {@link #reject} - REQ-11, REQ-14</li>
 *   <li>OP-07 {@link #expirePendingApprovals} - REQ-12</li>
 * </ul>
 *
 * <b>Deliberately left un-refactored for C03.</b> The duplication between
 * {@link #confirm} and {@link #approve} (both re-evaluate the same allocation
 * guards) and the {@code if}-cascade encoding of the statechart are exactly the
 * pressures recorded as architecture drivers AD-4 and AD-6. C02's job is a
 * running baseline that matches the specification, not the right structure.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    /** BR-03.1 (amended by v0.2): the cancellable source states. */
    private static final Set<ReservationState> CANCELLABLE_FROM = EnumSet.of(
            ReservationState.DRAFT, ReservationState.PENDING_APPROVAL, ReservationState.CONFIRMED);

    private final ReservationRepository reservationRepository;
    private final CourtRepository courtRepository;
    private final AppUserRepository userRepository;
    private final NotificationService notificationService;
    private final ApprovalPolicy approvalPolicy;
    private final LazyApprovalExpiry lazyApprovalExpiry;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              CourtRepository courtRepository,
                              AppUserRepository userRepository,
                              NotificationService notificationService,
                              ApprovalPolicy approvalPolicy,
                              LazyApprovalExpiry lazyApprovalExpiry,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.courtRepository = courtRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.approvalPolicy = approvalPolicy;
        this.lazyApprovalExpiry = lazyApprovalExpiry;
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

        int pending = countLivePendingApprovals(courtId, start, end);                    // REQ-13

        if (!court.isActive()) {                                                        // B3 / BR-07
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.COURT_INACTIVE, gated, pending);
        }
        if (!court.covers(start, end)) {                                                // B4 / BR-04
            return AvailabilityResult.blocked(
                    AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS, gated, pending);
        }
        if (!isSlotFree(courtId, start, end)) {                                         // B5 / BR-02
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
     * REQ-04 / REQ-15: the check-then-act window is serialised per court, so two
     * conflicting confirms cannot both pass the overlap check.
     */
    @Transactional
    public Reservation confirm(Long actorId, Long reservationId) {
        AppUser actor = requireActor(actorId);                      // BR-05
        Reservation reservation = requireReservation(reservationId);// C1
        requireAuthorizedFor(actor, reservation);                   // BR-05 (C4)

        // REQ-04 / REQ-15: exclusive hold for the whole check-then-act window.
        Court court = lockCourt(reservation.getCourt().getId());

        requireState(reservation, ReservationState.DRAFT,           // C2, C3
                "Only a DRAFT reservation can be confirmed");
        requireAllocatable(court, reservation);                     // C5, C6, C7

        if (court.isRequiresApproval()) {                          // BR-08 / REQ-09
            LocalDateTime now = LocalDateTime.now(clock);          // BR-06, read once
            reservation.submitForApproval(approvalPolicy.deadlineFor(now, reservation.getStartTime()));
            Reservation saved = reservationRepository.save(reservation);
            notifyQuietly(saved, notificationService::notifyApprovalPending);
            return saved;
        }

        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);
        notifyQuietly(saved, notificationService::notifyConfirmed);  // C9 - must not undo it
        return saved;
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
    // OP-05 Approve Reservation
    // ==================================================================

    /**
     * OP-05 / REQ-10: an authorized person grants the facility's capacity.
     *
     * Every allocation guard of OP-03 is re-evaluated HERE, because time passed
     * while the request waited: the court may have been deactivated, its opening
     * hours may have changed, and another reservation may have become CONFIRMED
     * over the same slot. The approval, not the submission, is the moment the
     * allocation is committed.
     *
     * REQ-12 lazy expiry (E7): an attempt after the deadline does not merely fail,
     * it moves the request to EXPIRED - so correctness never depends on the
     * OP-07 sweep having run.
     */
    @Transactional
    public Reservation approve(Long approverId, Long reservationId) {
        AppUser approver = requireActor(approverId);                // BR-05
        Reservation reservation = requireReservation(reservationId);// E1
        requireApprovalAuthority(approver, reservation);            // BR-09 (E2, E3)

        // REQ-15: exclusive hold - the second allocating operation needs it too.
        Court court = lockCourt(reservation.getCourt().getId());

        requireState(reservation, ReservationState.PENDING_APPROVAL,        // E4, E5, E6
                "Only a PENDING_APPROVAL reservation can be approved");

        LocalDateTime now = LocalDateTime.now(clock);               // BR-06, read once
        if (!now.isBefore(reservation.getApprovalDeadline())) {     // E7 / BR-10, REQ-12
            // E7 is a FAILURE THAT CHANGES STATE. Throwing rolls this transaction
            // back, so the expiry is committed separately - see LazyApprovalExpiry.
            lazyApprovalExpiry.expire(reservation.getId(), clock.instant());
            throw new ReservationException(ReservationErrorCode.APPROVAL_EXPIRED,
                    "The approval deadline (" + reservation.getApprovalDeadline()
                            + ") has passed; the request is now EXPIRED.");
        }

        requireAllocatable(court, reservation);                     // E8, E9, E10

        reservation.approve(approver, clock.instant());
        Reservation saved = reservationRepository.save(reservation);
        notifyQuietly(saved, notificationService::notifyConfirmed);
        return saved;
    }

    // ==================================================================
    // OP-06 Reject Reservation
    // ==================================================================

    /**
     * OP-06 / REQ-11: an authorized person refuses the request, terminally.
     *
     * Note the asymmetry with {@link #approve}: rejection needs NONE of the
     * allocation guards (court active, opening hours, overlap). Those exist to
     * protect an allocation, and a rejection allocates nothing - refusing a
     * request on a court that has meanwhile closed must remain possible.
     * F5: the approval deadline does not limit recording a refusal either.
     */
    @Transactional
    public Reservation reject(Long approverId, Long reservationId, String reason) {
        AppUser approver = requireActor(approverId);                // BR-05
        Reservation reservation = requireReservation(reservationId);// F1
        requireApprovalAuthority(approver, reservation);            // BR-09 (F2, F3)

        requireState(reservation, ReservationState.PENDING_APPROVAL,        // F4
                "Only a PENDING_APPROVAL reservation can be rejected");

        reservation.reject(approver, clock.instant(), reason);      // BR-11 - terminal
        Reservation saved = reservationRepository.save(reservation);
        notifyQuietly(saved, notificationService::notifyDecision);
        return saved;
    }

    // ==================================================================
    // OP-07 Expire Pending Approvals
    // ==================================================================

    /**
     * OP-07 / REQ-12: the sweep. Triggered by TIME, not by an actor - the one
     * operation in the system with no human actor (architecture driver AD-5).
     *
     * Idempotent and safe at any frequency (G1). Correctness does not depend on
     * it (G2): {@link #approve} expires a due request lazily. Expiry never changes
     * availability, because a pending request never blocked (decision D-2).
     *
     * @return how many reservations were expired
     */
    @Transactional
    public int expirePendingApprovals() {
        LocalDateTime now = LocalDateTime.now(clock);               // BR-06
        List<Reservation> due = reservationRepository.findDuePendingApprovals(now);
        for (Reservation reservation : due) {
            reservation.expire(clock.instant());
            reservationRepository.save(reservation);
            notifyQuietly(reservation, notificationService::notifyDecision);
        }
        if (!due.isEmpty()) {
            log.info("OP-07: expired {} pending approval request(s) at {}", due.size(), now);
        }
        return due.size();
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

    /**
     * BR-09: an approval decision requires STAFF (clause 1) who is not the owner
     * (clause 2, separation of duty). This is the one place STAFF are MORE
     * restricted than under BR-05.
     */
    private void requireApprovalAuthority(AppUser actor, Reservation reservation) {
        if (actor.getRole() != AppUser.Role.STAFF) {                            // BR-09.1
            throw new ReservationException(ReservationErrorCode.UNAUTHORIZED,
                    "Only STAFF may decide approval requests; actor " + actor.getId()
                            + " is " + actor.getRole() + ".");
        }
        if (actor.getId().equals(reservation.getUser().getId())) {               // BR-09.2
            throw new ReservationException(ReservationErrorCode.SELF_APPROVAL,
                    "An approver may not decide their own reservation " + reservation.getId() + ".");
        }
    }

    private void requireState(Reservation reservation, ReservationState expected, String message) {
        if (reservation.getState() != expected) {
            throw new ReservationException(ReservationErrorCode.INVALID_STATE,
                    message + "; state was " + reservation.getState() + ".");
        }
    }

    /**
     * The guards that protect an ALLOCATION (BR-07, BR-04, BR-02). Shared by
     * OP-03 and OP-05 because REQ-10 requires them re-evaluated at approval time.
     *
     * That both allocating operations must remember to call this is precisely the
     * fragility recorded as architecture driver AD-6: the invariant should be
     * enforceable independently of which operation runs.
     */
    private void requireAllocatable(Court court, Reservation reservation) {
        if (!court.isActive()) {                                                 // C5 / E8, BR-07
            throw new ReservationException(ReservationErrorCode.COURT_INACTIVE,
                    "Court '" + court.getName() + "' is not taking bookings.");
        }
        if (!court.covers(reservation.getStartTime(), reservation.getEndTime())) {  // C6 / E9, BR-04
            throw new ReservationException(ReservationErrorCode.OUTSIDE_OPENING_HOURS, String.format(
                    "Reservation must lie within opening hours %s-%s of court '%s' on a single day.",
                    court.getOpeningTime(), court.getClosingTime(), court.getName()));
        }
        if (!isSlotFree(court.getId(), reservation.getStartTime(), reservation.getEndTime())) {
            throw new ReservationException(ReservationErrorCode.CONFLICT,         // C7 / E10, BR-02
                    "Slot overlaps an existing CONFIRMED reservation of this court.");
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

    /**
     * REQ-13: how many requests are still genuinely waiting for this interval?
     * A request whose deadline has passed but which the sweep has not reached yet
     * is NOT counted - it can no longer be approved (E7), so reporting it as
     * contention would overstate the risk.
     */
    private int countLivePendingApprovals(Long courtId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now(clock);
        return (int) reservationRepository
                .findOverlapping(courtId, EnumSet.of(ReservationState.PENDING_APPROVAL), start, end)
                .stream()
                .filter(r -> r.getApprovalDeadline() != null && now.isBefore(r.getApprovalDeadline()))
                .count();
    }

    /** C9 / C01 ADR-3: a notification failure must not undo a committed state change. */
    private void notifyQuietly(Reservation reservation, NotifyCall call) {
        try {
            call.accept(reservation);
        } catch (NotificationException e) {
            log.warn("Notification failed for reservation {}: {}", reservation.getId(), e.getMessage());
        }
    }

    /** A {@link Consumer} that may fail at the boundary. */
    @FunctionalInterface
    private interface NotifyCall {
        void accept(Reservation reservation) throws NotificationException;
    }
}
