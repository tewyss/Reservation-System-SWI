package org.example.reservation.service;

/** Raised when a reservation operation violates a business rule. */
public class ReservationException extends RuntimeException {
    public ReservationException(String message) {
        super(message);
    }
}
