package org.example.reservation.service;

/**
 * Raised when an operation cannot produce its success postcondition.
 *
 * Carries the {@link ReservationErrorCode} named by the specification slice, so
 * a verification example asserts the specified outcome rather than a message.
 */
public class ReservationException extends RuntimeException {

    private final ReservationErrorCode code;

    public ReservationException(ReservationErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ReservationErrorCode getCode() {
        return code;
    }
}
