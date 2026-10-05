package org.example.reservation.service;

import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The one owner of the edge PENDING_APPROVAL -> EXPIRED (C03 ADR-7, responsibility
 * R4), used by BOTH ways a request expires:
 * <ul>
 *   <li>lazily, when an approval is attempted on or after the deadline (OP-05 E7);</li>
 *   <li>by the periodic sweep (OP-07), one reservation at a time.</li>
 * </ul>
 *
 * Each expiry commits in its OWN transaction. For E7 that is what lets a failure
 * persist state: the approval is rejected with APPROVAL_EXPIRED, and throwing rolls
 * the approval's transaction back - the expiry must survive that (mismatch M-2,
 * driver AD-8). For the sweep it means one conflicting row no longer rolls back
 * every other expiry in the batch (finding F-A6).
 *
 * It only writes the reservation row, never the court row an approving caller
 * holds a lock on, so it cannot deadlock with that caller.
 */
@Component
public class ApprovalExpiry {

    private final ReservationRepository reservationRepository;
    private final NotificationDispatcher notifications;

    public ApprovalExpiry(ReservationRepository reservationRepository,
                          NotificationDispatcher notifications) {
        this.reservationRepository = reservationRepository;
        this.notifications = notifications;
    }

    /**
     * Commit {@code PENDING_APPROVAL -> EXPIRED} independently of any caller's
     * transaction. Idempotent: a reservation that is no longer PENDING_APPROVAL is
     * left untouched (cancelled or decided in the meantime - outcome G3).
     *
     * @return whether this call expired the reservation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(Long reservationId, Instant at) {
        return reservationRepository.findById(reservationId)
                .filter(r -> r.getState() == ReservationState.PENDING_APPROVAL)
                .map(r -> {
                    r.expire(at);
                    reservationRepository.save(r);
                    notifications.decision(r);                      // after this transaction commits
                    return true;
                })
                .orElse(false);
    }
}
