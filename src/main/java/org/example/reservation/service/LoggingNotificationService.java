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
 */
@Service
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    @Override
    public void notifyConfirmed(Reservation reservation) {
        log.info("NOTIFY owner {}: reservation {} for court '{}' is CONFIRMED",
                reservation.getUser().getEmail(), reservation.getId(),
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
        log.info("NOTIFY owner {}: reservation {} is now {}{}",
                reservation.getUser().getEmail(), reservation.getId(), reservation.getState(),
                reservation.getDecisionReason() == null ? "" : " (" + reservation.getDecisionReason() + ")");
    }
}
