package org.example.reservation.service;

import org.example.reservation.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Notification Integration - C03 ADR-7 (driver DR-4).
 *
 * The only caller of the {@link NotificationService} boundary. It fixes two
 * things the AS-IS code left to each call site:
 * <ul>
 *   <li><b>When:</b> after the business transaction commits. A message sent
 *       before the commit could announce an outcome that then rolls back - the
 *       AS-IS run of V-06.5 told the owner both REJECTED and CONFIRMED (F-A5).</li>
 *   <li><b>What a failure means:</b> nothing for the business state. It is logged
 *       and the committed outcome stands (C01 ADR-3, OP-03 C9). There is no retry:
 *       delivery is at most once - an accepted consequence recorded in ADR-7.</li>
 * </ul>
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationService notificationService;

    public NotificationDispatcher(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The owner's reservation is CONFIRMED (OP-03 self-service, OP-05). */
    public void confirmed(Reservation reservation) {
        dispatch(reservation, notificationService::notifyConfirmed);
    }

    /** A request on a gated court awaits an approver's decision (OP-03, REQ-09). */
    public void approvalPending(Reservation reservation) {
        dispatch(reservation, notificationService::notifyApprovalPending);
    }

    /** The owner's request was rejected or expired (OP-06, OP-05 E7, OP-07). */
    public void decision(Reservation reservation) {
        dispatch(reservation, notificationService::notifyDecision);
    }

    private void dispatch(Reservation reservation, NotifyCall call) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendQuietly(reservation, call);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendQuietly(reservation, call);
            }
        });
    }

    private void sendQuietly(Reservation reservation, NotifyCall call) {
        try {
            call.send(reservation);
        } catch (NotificationException e) {
            log.warn("Notification failed for reservation {}: {}", reservation.getId(), e.getMessage());
        }
    }

    /** One occasion of the boundary, which may fail. */
    @FunctionalInterface
    private interface NotifyCall {
        void send(Reservation reservation) throws NotificationException;
    }
}
