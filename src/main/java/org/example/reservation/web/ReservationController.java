package org.example.reservation.web;

import org.example.reservation.domain.Reservation;
import org.example.reservation.service.ReservationException;
import org.example.reservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST entry point. Implements the start of the CP1 walking skeleton:
 * {@code POST /reservations -> validate -> persist -> return reservation ID}.
 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** CP1 walking skeleton: create a DRAFT reservation and return its ID. */
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@RequestBody CreateReservationRequest request) {
        Reservation reservation = reservationService.create(
                request.courtId(), request.userId(), request.start(), request.end());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @PostMapping("/{id}/confirm")
    public ReservationResponse confirm(@PathVariable Long id) {
        return ReservationResponse.from(reservationService.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable Long id) {
        return ReservationResponse.from(reservationService.cancel(id));
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(@RequestParam Long courtId,
                                             @RequestParam LocalDateTime start,
                                             @RequestParam LocalDateTime end) {
        return new AvailabilityResponse(reservationService.isAvailable(courtId, start, end));
    }

    @ExceptionHandler(ReservationException.class)
    public ResponseEntity<String> handleBusinessRule(ReservationException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    public record CreateReservationRequest(Long courtId, Long userId,
                                           LocalDateTime start, LocalDateTime end) {
    }

    public record ReservationResponse(Long id, Long courtId, Long userId,
                                      LocalDateTime start, LocalDateTime end, String state) {
        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getId(), r.getCourt().getId(), r.getUser().getId(),
                    r.getStartTime(), r.getEndTime(), r.getState().name());
        }
    }

    public record AvailabilityResponse(boolean available) {
    }
}
