package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Application service holding the four core operations and the business rules.
 *
 * <ul>
 *   <li>Create reservation (as DRAFT)</li>
 *   <li>Confirm / approve reservation (DRAFT -> CONFIRMED)</li>
 *   <li>Cancel reservation (-> CANCELLED)</li>
 *   <li>Check availability</li>
 * </ul>
 *
 * Business rules enforced at confirm time:
 * <ol>
 *   <li><b>Common overlap rule:</b> two CONFIRMED reservations of the same court
 *       must not overlap.</li>
 *   <li><b>Domain-specific opening-hours rule:</b> a reservation must lie entirely
 *       within the court's opening hours.</li>
 * </ol>
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final CourtRepository courtRepository;
    private final AppUserRepository userRepository;
    private final NotificationService notificationService;

    public ReservationService(ReservationRepository reservationRepository,
                              CourtRepository courtRepository,
                              AppUserRepository userRepository,
                              NotificationService notificationService) {
        this.reservationRepository = reservationRepository;
        this.courtRepository = courtRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /** Create a new reservation in DRAFT state (does not yet block the court). */
    @Transactional
    public Reservation create(Long courtId, Long userId, LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) {
            throw new ReservationException("Reservation start must be before end.");
        }
        Court court = courtRepository.findById(courtId)
                .orElseThrow(() -> new ReservationException("Court not found: " + courtId));
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ReservationException("User not found: " + userId));

        Reservation reservation = new Reservation(court, user, start, end);
        return reservationRepository.save(reservation);
    }

    /**
     * Confirm a DRAFT reservation. Enforces both business rules before moving it
     * to CONFIRMED, then notifies via the boundary. A notification failure does
     * NOT roll back the confirmation.
     */
    @Transactional
    public Reservation confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationException("Reservation not found: " + reservationId));

        if (reservation.getState() != ReservationState.DRAFT) {
            throw new ReservationException(
                    "Only DRAFT reservations can be confirmed; was " + reservation.getState());
        }

        // Domain-specific rule: within opening hours.
        enforceOpeningHours(reservation);

        // Common rule: no overlap with other CONFIRMED reservations of the same court.
        if (!isSlotFree(reservation.getCourt().getId(),
                reservation.getStartTime(), reservation.getEndTime())) {
            throw new ReservationException(
                    "Slot overlaps an existing confirmed reservation for this court.");
        }

        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);

        try {
            notificationService.notifyConfirmed(saved);
        } catch (NotificationException e) {
            // Boundary failure must not undo a valid confirmation.
            log.warn("Notification failed for reservation {}: {}", saved.getId(), e.getMessage());
        }
        return saved;
    }

    /** Cancel a reservation (from DRAFT or CONFIRMED), freeing the court. */
    @Transactional
    public Reservation cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationException("Reservation not found: " + reservationId));
        if (reservation.getState() == ReservationState.CANCELLED) {
            throw new ReservationException("Reservation is already cancelled.");
        }
        reservation.cancel();
        return reservationRepository.save(reservation);
    }

    /**
     * Check availability: is [start, end) free of CONFIRMED reservations for the
     * court AND within the court's opening hours?
     */
    @Transactional(readOnly = true)
    public boolean isAvailable(Long courtId, LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) {
            return false;
        }
        Court court = courtRepository.findById(courtId)
                .orElseThrow(() -> new ReservationException("Court not found: " + courtId));
        return withinOpeningHours(court, start, end) && isSlotFree(courtId, start, end);
    }

    private boolean isSlotFree(Long courtId, LocalDateTime start, LocalDateTime end) {
        List<Reservation> clashes = reservationRepository.findOverlapping(
                courtId, ReservationState.CONFIRMED, start, end);
        return clashes.isEmpty();
    }

    private void enforceOpeningHours(Reservation reservation) {
        if (!withinOpeningHours(reservation.getCourt(),
                reservation.getStartTime(), reservation.getEndTime())) {
            Court court = reservation.getCourt();
            throw new ReservationException(String.format(
                    "Reservation must be within opening hours %s-%s for court '%s'.",
                    court.getOpeningTime(), court.getClosingTime(), court.getName()));
        }
    }

    private boolean withinOpeningHours(Court court, LocalDateTime start, LocalDateTime end) {
        boolean sameDay = start.toLocalDate().equals(end.toLocalDate());
        boolean afterOpen = !start.toLocalTime().isBefore(court.getOpeningTime());
        boolean beforeClose = !end.toLocalTime().isAfter(court.getClosingTime());
        return sameDay && afterOpen && beforeClose;
    }
}
