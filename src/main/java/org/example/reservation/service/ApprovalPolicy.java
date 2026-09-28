package org.example.reservation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * BR-10: computes the approval deadline of a request entering PENDING_APPROVAL.
 *
 * <pre>approvalDeadline = min( now + approvalWindow , reservation.start )</pre>
 *
 * The {@code min(..., start)} part is an INVARIANT the team can justify: an
 * approval granted after the slot has begun cannot be honoured.
 *
 * The window itself is a CONFIGURATION value, not a business constant - no
 * stakeholder supplied a figure (assumption A-7). It is externalised precisely so
 * that changing it is configuration rather than a code change.
 */
@Component
public class ApprovalPolicy {

    private final Duration approvalWindow;

    public ApprovalPolicy(@Value("${reservation.approval.window:PT24H}") Duration approvalWindow) {
        this.approvalWindow = approvalWindow;
    }

    /** BR-10. */
    public LocalDateTime deadlineFor(LocalDateTime now, LocalDateTime reservationStart) {
        LocalDateTime windowEnd = now.plus(approvalWindow);
        return windowEnd.isBefore(reservationStart) ? windowEnd : reservationStart;
    }

    public Duration getApprovalWindow() {
        return approvalWindow;
    }
}
