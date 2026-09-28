package org.example.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;


@Entity
@Table(name = "court")
public class Court {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourtType type;

    @Column(nullable = false)
    private String location;

    /** Earliest time the court may be used (inclusive). */
    @Column(nullable = false)
    private LocalTime openingTime;

    /** Latest time the court may still be in use (exclusive end bound). */
    @Column(nullable = false)
    private LocalTime closingTime;

    /** BR-07: an inactive court takes no new bookings but keeps its history. */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * BR-08 (v0.2): a gated court requires an approver's decision before a
     * reservation may become CONFIRMED. Read at confirm time (A-11: toggling it
     * does not reclassify requests already submitted).
     */
    @Column(nullable = false)
    private boolean requiresApproval = false;

    protected Court() {
        // required by JPA
    }

    public Court(String name, CourtType type, String location,
                 LocalTime openingTime, LocalTime closingTime) {
        this(name, type, location, openingTime, closingTime, true, false);
    }

    public Court(String name, CourtType type, String location,
                 LocalTime openingTime, LocalTime closingTime, boolean active) {
        this(name, type, location, openingTime, closingTime, active, false);
    }

    public Court(String name, CourtType type, String location,
                 LocalTime openingTime, LocalTime closingTime,
                 boolean active, boolean requiresApproval) {
        this.name = name;
        this.type = type;
        this.location = location;
        this.openingTime = openingTime;
        this.closingTime = closingTime;
        this.active = active;
        this.requiresApproval = requiresApproval;
    }

    /**
     * BR-04: does {@code [start, end)} lie inside this court's opening hours and
     * within a single calendar day?
     */
    public boolean covers(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        boolean sameDay = start.toLocalDate().equals(end.toLocalDate());
        boolean afterOpen = !start.toLocalTime().isBefore(openingTime);
        boolean beforeClose = !end.toLocalTime().isAfter(closingTime);
        return sameDay && afterOpen && beforeClose;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CourtType getType() {
        return type;
    }

    public String getLocation() {
        return location;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    public boolean isActive() {
        return active;
    }

    /** BR-08: does this court need an approver's decision before CONFIRMED? */
    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public void deactivate() {
        this.active = false;
    }

    public enum CourtType {
        TENNIS, SQUASH, BADMINTON, BASKETBALL, FOOTBALL
    }
}
