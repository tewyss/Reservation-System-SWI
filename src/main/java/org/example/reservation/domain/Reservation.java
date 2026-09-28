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

    public void confirm() {
        this.state = ReservationState.CONFIRMED;
    }

    public void cancel(Instant at) {
        this.state = ReservationState.CANCELLED;
        this.cancelledAt = at;
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
}
