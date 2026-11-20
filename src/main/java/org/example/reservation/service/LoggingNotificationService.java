package org.example.reservation.service;

import org.example.reservation.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of the Notification boundary.
 *
 * It only logs; a later checkpoint will call a real provider. It is the concrete
 * dependency wired into {@link ReservationService}.
 *
 * C10: the log names the owner by user id, never by e-mail - an operational log
 * must not carry personal data (the provider, not the log, needs the address).
 */
@Service
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    @Override
    public void notifyConfirmed(Reservation reservation) {
        log.info("NOTIFY owner user#{}: reservation {} for court '{}' is CONFIRMED",
                reservation.getUser().getId(), reservation.getId(),
                reservation.getCourt().getName());
    }

    @Override
    public void notifyApprovalPending(Reservation reservation) {
        log.info("NOTIFY approvers: reservation {} for court '{}' awaits approval until {}",
                reservation.getId(), reservation.getCourt().getName(),
                reservation.getApprovalDeadline());
    }

    @Override
    public void notifyDecision(Reservation reservation) {
        log.info("NOTIFY owner user#{}: reservation {} is now {}{}",
                reservation.getUser().getId(), reservation.getId(), reservation.getState(),
                reservation.getDecisionReason() == null ? "" : " (" + reservation.getDecisionReason() + ")");
    }
}
