package org.example.reservation.service;

import org.example.reservation.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of the Notification boundary.
 *
 * For C01 it only logs; in later checkpoints it will call a real provider.
 * It is the concrete dependency wired into {@link ReservationService}.
 */
@Service
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    @Override
    public void notifyConfirmed(Reservation reservation) throws NotificationException {
        log.info("Notification: reservation {} for court '{}' confirmed for user {}",
                reservation.getId(),
                reservation.getCourt().getName(),
                reservation.getUser().getEmail());
    }
}
