package org.example.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "court_id", nullable = false)
    private Court court;

    /** The reservation's OWNER (BR-05); not necessarily the actor of an operation. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationState state;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** BR-03.4: cancel is not delete - the record is kept and stays auditable. */
    @Column
    private Instant cancelledAt;

    /**
     * BR-10 (v0.2): fixed when the reservation enters PENDING_APPROVAL as
     * min(now + approvalWindow, startTime). On or after it the request is no
     * longer approvable. Null unless the reservation was ever submitted to an
     * approval-gated court.
     */
    @Column
    private LocalDateTime approvalDeadline;

    /** BR-09 / REQ-11: who decided (approved or rejected) this request. */
    @ManyToOne
    @JoinColumn(name = "decided_by_id")
    private AppUser decidedBy;

    @Column
    private Instant decidedAt;

    /** Optional free-text reason an approver gave when rejecting (OP-06). */
    @Column(length = 500)
    private String decisionReason;

    /**
     * C03 ADR-7: every transition is a compare-and-set on this row. Two operations
     * deciding the same reservation at once (approve vs reject, approve vs cancel,
     * two sweeps) cannot both commit - the second UPDATE matches no row and its
     * whole transaction rolls back (V-06.5, V-04.11).
     */
    @Version
    private long version;

    protected Reservation() {
        // required by JPA
    }

    public Reservation(Court court, AppUser user, LocalDateTime startTime, LocalDateTime endTime,
                       Instant createdAt) {
        this.court = court;
        this.user = user;
        this.startTime = startTime;
        this.endTime = endTime;
        this.state = ReservationState.DRAFT;
        this.createdAt = createdAt;
    }

    /** BR-01: true if this reservation's slot overlaps {@code [otherStart, otherEnd)}. */
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return startTime.isBefore(otherEnd) && otherStart.isBefore(endTime);
    }

    /** BR-02: does this reservation block the court for its interval? */
    public boolean blocksCourt() {
        return state.blocksCourt();
    }

    /**
     * OP-03 on a self-service court: DRAFT -> CONFIRMED.
     * Only Court Allocation may call this (C03 ADR-6, checked by C03ArchitectureRuleTest).
     */
    public void confirm() {
        moveTo(ReservationState.CONFIRMED);
    }

    /** OP-03 on an approval-gated court (REQ-09): DRAFT -> PENDING_APPROVAL. */
    public void submitForApproval(LocalDateTime deadline) {
        moveTo(ReservationState.PENDING_APPROVAL);
        this.approvalDeadline = deadline;
    }

    /**
     * OP-05 (REQ-10): PENDING_APPROVAL -> CONFIRMED, recording the decision.
     * Only Court Allocation may call this (C03 ADR-6, checked by C03ArchitectureRuleTest).
     */
    public void approve(AppUser approver, Instant at) {
        moveTo(ReservationState.CONFIRMED);
        this.decidedBy = approver;
        this.decidedAt = at;
    }

    /** OP-06 (REQ-11): PENDING_APPROVAL -> REJECTED, recording the decision. */
    public void reject(AppUser approver, Instant at, String reason) {
        moveTo(ReservationState.REJECTED);
        this.decidedBy = approver;
        this.decidedAt = at;
        this.decisionReason = reason;
    }

    /** OP-05 E7 / OP-07 (REQ-12): PENDING_APPROVAL -> EXPIRED. No actor decided. */
    public void expire(Instant at) {
        moveTo(ReservationState.EXPIRED);
        this.decidedAt = at;
    }

    public void cancel(Instant at) {
        moveTo(ReservationState.CANCELLED);
        this.cancelledAt = at;
    }

    /**
     * R5: the entity refuses an edge the statechart does not have, whoever calls
     * it. The operations check the source state first and report INVALID_STATE;
     * reaching this exception means a caller skipped that check - a defect, not a
     * business outcome.
     */
    private void moveTo(ReservationState target) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException("The lifecycle statechart has no edge "
                    + state + " -> " + target + " (reservation " + id + ").");
        }
        this.state = target;
    }

    public Long getId() {
        return id;
    }

    public Court getCourt() {
        return court;
    }

    public AppUser getUser() {
        return user;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public ReservationState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public LocalDateTime getApprovalDeadline() {
        return approvalDeadline;
    }

    public AppUser getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public long getVersion() {
        return version;
    }
}
