package org.example.reservation.service;

import org.example.reservation.domain.Reservation;

/**
 * EXTERNAL / SYSTEM BOUNDARY.
 *
 * The reservation system does not send messages itself; it delegates to a
 * Notification Service dependency. This interface is the seam that lets us
 * swap a real provider (email/SMS/push) for a stub in tests, and lets us
 * handle the boundary failing (timeout/error) without breaking reservations.
 */
public interface NotificationService {

    /**
     * Notify the reservation's owner that it has been confirmed.
     *
     * @throws NotificationException if the boundary is unreachable / times out
     */
    void notifyConfirmed(Reservation reservation) throws NotificationException;
}
