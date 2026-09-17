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

/**
 * The reserved RESOURCE: a bookable sports court (e.g. tennis, squash).
 * Each court has opening hours that constrain when it can be reserved.
 */
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

    protected Court() {
        // required by JPA
    }

    public Court(String name, CourtType type, String location,
                 LocalTime openingTime, LocalTime closingTime) {
        this.name = name;
        this.type = type;
        this.location = location;
        this.openingTime = openingTime;
        this.closingTime = closingTime;
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

    public enum CourtType {
        TENNIS, SQUASH, BADMINTON, BASKETBALL, FOOTBALL
    }
}
