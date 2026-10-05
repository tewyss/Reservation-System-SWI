package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Approval Workflow - the approval process of Specification Baseline v0.2.
 *
 * Owns the pending approval state: the deadline (BR-10), the decision (BR-09,
 * REQ-11) and the edges PENDING_APPROVAL -> REJECTED / EXPIRED. It REQUESTS the
 * edge PENDING_APPROVAL -> CONFIRMED from {@link CourtAllocation}, which decides
 * it (C03 ADR-6, statechart ownership table G3).
 *
 * <ul>
 *   <li>{@link #submit} - entering PENDING_APPROVAL, on behalf of OP-03 (REQ-09)</li>
 *   <li>OP-05 {@link #approve} - REQ-10, REQ-14, REQ-15</li>
 *   <li>OP-06 {@link #reject} - REQ-11, REQ-14</li>
 *   <li>OP-07 {@link #expirePendingApprovals} - REQ-12</li>
 * </ul>
 */
@Service
public class ApprovalWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflow.class);

    private final ReservationRepository reservationRepository;
    private final AppUserRepository userRepository;
    private final CourtAllocation courtAllocation;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalExpiry approvalExpiry;
    private final NotificationDispatcher notifications;
    private final Clock clock;

    public ApprovalWorkflow(ReservationRepository reservationRepository,
                            AppUserRepository userRepository,
                            CourtAllocation courtAllocation,
                            ApprovalPolicy approvalPolicy,
                            ApprovalExpiry approvalExpiry,
                            NotificationDispatcher notifications,
                            Clock clock) {
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.courtAllocation = courtAllocation;
        this.approvalPolicy = approvalPolicy;
        this.approvalExpiry = approvalExpiry;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ==================================================================
    // Entering the workflow (requested by OP-03 on a gated court)
    // ==================================================================

    /**
     * REQ-09: DRAFT -> PENDING_APPROVAL with a BR-10 deadline; nothing allocated.
     * The caller (Reservation Lifecycle) has already authorized the actor, checked
     * DRAFT and had the request's admissibility checked by Court Allocation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(Reservation reservation) {
        LocalDateTime now = LocalDateTime.now(clock);              // BR-06, read once
        reservation.submitForApproval(approvalPolicy.deadlineFor(now, reservation.getStartTime()));
        reservationRepository.save(reservation);
        notifications.approvalPending(reservation);
    }

    // ==================================================================
    // OP-05 Approve Reservation
    // ==================================================================

    /**
     * OP-05 / REQ-10: an authorized person grants the facility's capacity.
     *
     * This element decides the APPROVAL preconditions (BR-09 authority, BR-10
     * deadline); the ALLOCATION - every guard of OP-03 re-evaluated now, because
     * time passed while the request waited - is decided by Court Allocation.
     *
     * REQ-12 lazy expiry (E7): an attempt after the deadline does not merely fail,
     * it moves the request to EXPIRED - so correctness never depends on the OP-07
     * sweep having run.
     */
    @Transactional
    public Reservation approve(Long approverId, Long reservationId) {
        AppUser approver = requireActor(approverId);                // BR-05
        Reservation reservation = requireReservation(reservationId);// E1
        requireApprovalAuthority(approver, reservation);            // BR-09 (E2, E3)

        CourtHold hold = courtAllocation.hold(reservation);         // REQ-15

        requirePending(reservation, "Only a PENDING_APPROVAL reservation can be approved"); // E4-E6

        LocalDateTime now = LocalDateTime.now(clock);               // BR-06, read once
        if (!now.isBefore(reservation.getApprovalDeadline())) {     // E7 / BR-10, REQ-12
            // A FAILURE THAT CHANGES STATE: the expiry commits on its own,
            // because throwing rolls this transaction back.
            approvalExpiry.expire(reservation.getId(), clock.instant());
            throw new ReservationException(ReservationErrorCode.APPROVAL_EXPIRED,
                    "The approval deadline (" + reservation.getApprovalDeadline()
                            + ") has passed; the request is now EXPIRED.");
        }

        courtAllocation.confirmApproved(hold, reservation, approver, clock.instant()); // E8-E10
        notifications.confirmed(reservation);
        return reservation;
    }

    // ==================================================================
    // OP-06 Reject Reservation
    // ==================================================================

    /**
     * OP-06 / REQ-11: an authorized person refuses the request, terminally.
     *
     * Takes no court hold: a rejection allocates nothing, so none of the
     * allocation guards apply (C02 OP-06 note). A race with a concurrent approval
     * or cancel of the SAME reservation is decided by the row version instead
     * (C03 ADR-7, V-06.5). F5: the deadline does not limit recording a refusal.
     */
    @Transactional
    public Reservation reject(Long approverId, Long reservationId, String reason) {
        AppUser approver = requireActor(approverId);                // BR-05
        Reservation reservation = requireReservation(reservationId);// F1
        requireApprovalAuthority(approver, reservation);            // BR-09 (F2, F3)

        requirePending(reservation, "Only a PENDING_APPROVAL reservation can be rejected"); // F4

        reservation.reject(approver, clock.instant(), reason);      // BR-11 - terminal
        reservationRepository.save(reservation);
        notifications.decision(reservation);
        return reservation;
    }

    // ==================================================================
    // OP-07 Expire Pending Approvals
    // ==================================================================

    /**
     * OP-07 / REQ-12: the sweep. Triggered by TIME, not by an actor (driver AD-5).
     *
     * Deliberately not one transaction: each due request is expired in its own
     * (see {@link ApprovalExpiry}). A request a concurrent cancel, decision or a
     * second instance's sweep got to first is skipped - its outcome stands and no
     * second notification is sent (C03 ADR-7).
     *
     * Idempotent and safe at any frequency (G1). Correctness does not depend on it
     * (G2). Expiry never changes availability - a pending request never blocked.
     *
     * @return how many reservations this run expired
     */
    public int expirePendingApprovals() {
        LocalDateTime now = LocalDateTime.now(clock);               // BR-06
        Instant at = clock.instant();
        int expired = 0;
        for (Reservation due : reservationRepository.findDuePendingApprovals(now)) {
            try {
                if (approvalExpiry.expire(due.getId(), at)) {
                    expired++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("OP-07: reservation {} was changed concurrently; its outcome stands", due.getId());
            }
        }
        if (expired > 0) {
            log.info("OP-07: expired {} pending approval request(s) at {}", expired, now);
        }
        return expired;
    }

    // ==================================================================
    // Disclosure for OP-02 (REQ-13)
    // ==================================================================

    /**
     * REQ-13: how many requests are still genuinely waiting for this interval?
     * A request whose deadline has passed but which the sweep has not reached yet
     * is NOT counted - it can no longer be approved (E7), so reporting it as
     * contention would overstate the risk.
     */
    @Transactional(readOnly = true)
    public int livePendingCount(Long courtId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now(clock);
        return (int) reservationRepository
                .findOverlapping(courtId, EnumSet.of(ReservationState.PENDING_APPROVAL), start, end)
                .stream()
                .filter(r -> r.getApprovalDeadline() != null && now.isBefore(r.getApprovalDeadline()))
                .count();
    }

    // ==================================================================
    // Guards
    // ==================================================================

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

    private void requirePending(Reservation reservation, String message) {
        if (reservation.getState() != ReservationState.PENDING_APPROVAL) {
            throw new ReservationException(ReservationErrorCode.INVALID_STATE,
                    message + "; state was " + reservation.getState() + ".");
        }
    }

    private AppUser requireActor(Long actorId) {
        if (actorId == null) {
            throw new ReservationException(ReservationErrorCode.UNAUTHORIZED, "No actor supplied.");
        }
        return userRepository.findById(actorId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.UNAUTHORIZED, "Unknown actor: " + actorId));
    }

    private Reservation requireReservation(Long reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow(() -> new ReservationException(
                ReservationErrorCode.NOT_FOUND, "Reservation not found: " + reservationId));
    }
}
