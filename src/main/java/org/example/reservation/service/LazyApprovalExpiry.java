package org.example.reservation.service;

import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Makes OP-05 outcome E7 actually hold.
 *
 * E7 specifies a failure that CHANGES STATE: an approval attempted on or after the
 * deadline is rejected with APPROVAL_EXPIRED *and* the request becomes EXPIRED
 * (REQ-12, lazy expiry). Rejecting the attempt means throwing, and throwing rolls
 * the caller's transaction back - which would silently discard the state change
 * the specification requires.
 *
 * So the expiry is committed in its OWN transaction. It only ever writes the
 * reservation row, never the court row the caller holds a lock on, so it cannot
 * deadlock with the caller.
 *
 * <b>This is architecture material, not a trick:</b> "a failure outcome that must
 * persist state" has no home in the current layering, and the next such outcome
 * would repeat this collaborator. Recorded as driver AD-8 for C03.
 */
@Component
public class LazyApprovalExpiry {

    private final ReservationRepository reservationRepository;
    private final NotificationService notificationService;

    public LazyApprovalExpiry(ReservationRepository reservationRepository,
                              NotificationService notificationService) {
        this.reservationRepository = reservationRepository;
        this.notificationService = notificationService;
    }

    /**
     * Commit {@code PENDING_APPROVAL -> EXPIRED} independently of the caller's
     * transaction. Idempotent: a reservation that is no longer PENDING_APPROVAL is
     * left untouched (it was cancelled or decided in the meantime - outcome G3).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long reservationId, Instant at) {
        reservationRepository.findById(reservationId)
                .filter(r -> r.getState() == ReservationState.PENDING_APPROVAL)
                .ifPresent(r -> {
                    r.expire(at);
                    reservationRepository.save(r);
                    try {
                        notificationService.notifyDecision(r);
                    } catch (NotificationException e) {
                        // C01 ADR-3: boundary failure must not undo a committed change.
                    }
                });
    }
}
