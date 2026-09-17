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

/**
 * A RESERVATION: a request by an {@link AppUser} to use a {@link Court}
 * for a time slot [startTime, endTime), with a lifecycle {@link ReservationState}.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "court_id", nullable = false)
    private Court court;

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

    protected Reservation() {
        // required by JPA
    }

    public Reservation(Court court, AppUser user, LocalDateTime startTime, LocalDateTime endTime) {
        this.court = court;
        this.user = user;
        this.startTime = startTime;
        this.endTime = endTime;
        this.state = ReservationState.DRAFT;
        this.createdAt = Instant.now();
    }

    /** True if this reservation's slot overlaps the other's (half-open intervals). */
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return startTime.isBefore(otherEnd) && otherStart.isBefore(endTime);
    }

    public void confirm() {
        this.state = ReservationState.CONFIRMED;
    }

    public void cancel() {
        this.state = ReservationState.CANCELLED;
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
}
