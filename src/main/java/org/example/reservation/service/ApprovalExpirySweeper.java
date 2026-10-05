package org.example.reservation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OP-07's trigger: the periodic sweep that closes approval requests nobody
 * decided in time (REQ-12). Part of the Approval Workflow element; only the
 * trigger technology lives here, the transition is {@link ApprovalExpiry}'s.
 *
 * The sweep affects the TIMELINESS of reporting and notification, never
 * correctness - {@link ApprovalWorkflow#approve} expires a due request lazily
 * (outcome G2). That is what makes the frequency an operational choice (A-12)
 * rather than a business requirement.
 *
 * Disabled in tests so that verification examples control time themselves.
 */
@Component
@ConditionalOnProperty(name = "reservation.approval.sweep.enabled",
        havingValue = "true", matchIfMissing = true)
public class ApprovalExpirySweeper {

    private final ApprovalWorkflow approvalWorkflow;

    public ApprovalExpirySweeper(ApprovalWorkflow approvalWorkflow) {
        this.approvalWorkflow = approvalWorkflow;
    }

    @Scheduled(fixedDelayString = "${reservation.approval.sweep-interval:PT5M}")
    public void sweep() {
        approvalWorkflow.expirePendingApprovals();
    }
}
