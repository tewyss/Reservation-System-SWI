package org.example.reservation.service;

/** Raised when the external Notification Service boundary fails or times out. */
public class NotificationException extends Exception {
    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
