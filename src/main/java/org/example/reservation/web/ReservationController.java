package org.example.reservation.web;

import org.example.reservation.domain.Reservation;
import org.example.reservation.service.ApprovalWorkflow;
import org.example.reservation.service.AvailabilityResult;
import org.example.reservation.service.ReservationErrorCode;
import org.example.reservation.service.ReservationException;
import org.example.reservation.service.ReservationService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * HTTP surface of Specification Baseline v0.2: the four core operations plus the
 * approval workflow (approve, reject, expire).
 *
 * The actor (BR-05) is carried in the {@code X-Actor-Id} header. Assumption A-1:
 * the identity is asserted by the caller, not authenticated - establishing it is
 * architecture driver AD-3 for C03.
 *
 * Reservation API element (C03 G2): it decides nothing. Member operations go to
 * the Reservation Lifecycle ({@link ReservationService}), approval operations to
 * the {@link ApprovalWorkflow}.
 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ApprovalWorkflow approvalWorkflow;

    public ReservationController(ReservationService reservationService, ApprovalWorkflow approvalWorkflow) {
        this.reservationService = reservationService;
        this.approvalWorkflow = approvalWorkflow;
    }

    /** OP-01 Create Reservation. */
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
                                                      @RequestBody CreateReservationRequest request) {
        Reservation reservation = reservationService.create(
                actorId, request.courtId(), request.start(), request.end());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    /** OP-03 Confirm Reservation. */
    @PostMapping("/{id}/confirm")
    public ReservationResponse confirm(@RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
                                       @PathVariable Long id) {
        return ReservationResponse.from(reservationService.confirm(actorId, id));
    }

    /** OP-04 Cancel Reservation. */
    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
                                      @PathVariable Long id) {
        return ReservationResponse.from(reservationService.cancel(actorId, id));
    }

    /** OP-05 Approve Reservation (v0.2). */
    @PostMapping("/{id}/approve")
    public ReservationResponse approve(@RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
                                       @PathVariable Long id) {
        return ReservationResponse.from(approvalWorkflow.approve(actorId, id));
    }

    /** OP-06 Reject Reservation (v0.2). */
    @PostMapping("/{id}/reject")
    public ReservationResponse reject(@RequestHeader(value = "X-Actor-Id", required = false) Long actorId,
                                      @PathVariable Long id,
                                      @RequestBody(required = false) RejectRequest request) {
        return ReservationResponse.from(
                approvalWorkflow.reject(actorId, id, request == null ? null : request.reason()));
    }

    /**
     * OP-07 Expire Pending Approvals (v0.2) - triggered by time in production
     * (see ApprovalExpirySweeper); exposed here so the sweep can also be driven
     * explicitly when demonstrating the baseline.
     */
    @PostMapping("/expire-due")
    public ExpiryResponse expireDue() {
        return new ExpiryResponse(approvalWorkflow.expirePendingApprovals());
    }

    /** OP-02 Check Availability. No actor required - see consistency finding C-5. */
    @GetMapping("/availability")
    public AvailabilityResponse availability(@RequestParam Long courtId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        AvailabilityResult result = reservationService.checkAvailability(courtId, start, end);
        return new AvailabilityResponse(result.available(), result.reason().name(),
                result.approvalRequired(), result.pendingApprovalCount());
    }

    @ExceptionHandler(ReservationException.class)
    public ResponseEntity<ErrorResponse> handle(ReservationException e) {
        return ResponseEntity.status(statusFor(e.getCode()))
                .body(new ErrorResponse(e.getCode().name(), e.getMessage()));
    }

    /**
     * C03 ADR-7: another operation changed this reservation between our read and
     * our write (e.g. approve vs reject of the same request), so our transition
     * was rolled back. Per the C02 REQ-11 gate the loser "fails on the
     * source-state guard" - reported as INVALID_STATE; the client should re-read.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handle(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                ReservationErrorCode.INVALID_STATE.name(),
                "The reservation was changed by a concurrent operation; re-read its state."));
    }

    private static HttpStatus statusFor(ReservationErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED, SELF_APPROVAL -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, COURT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, INVALID_STATE, APPROVAL_EXPIRED -> HttpStatus.CONFLICT;
            case INVALID_INTERVAL, COURT_INACTIVE, OUTSIDE_OPENING_HOURS, TOO_LATE_TO_CANCEL ->
                    HttpStatus.BAD_REQUEST;
        };
    }

    public record CreateReservationRequest(Long courtId, LocalDateTime start, LocalDateTime end) {
    }

    public record RejectRequest(String reason) {
    }

    public record ExpiryResponse(int expired) {
    }

    public record ReservationResponse(Long id, Long courtId, Long ownerId,
                                      LocalDateTime start, LocalDateTime end, String state,
                                      LocalDateTime approvalDeadline, Long decidedBy) {
        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getId(), r.getCourt().getId(), r.getUser().getId(),
                    r.getStartTime(), r.getEndTime(), r.getState().name(),
                    r.getApprovalDeadline(),
                    r.getDecidedBy() == null ? null : r.getDecidedBy().getId());
        }
    }

    public record AvailabilityResponse(boolean available, String reason,
                                       boolean approvalRequired, int pendingApprovalCount) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
