package org.example.reservation.repository;

import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * Reservations for a court in a given state whose slot overlaps [start, end).
     * Used by the common overlap rule and the availability check.
     */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.court.id = :courtId
              AND r.state = :state
              AND r.startTime < :end
              AND :start < r.endTime
            """)
    List<Reservation> findOverlapping(@Param("courtId") Long courtId,
                                      @Param("state") ReservationState state,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);
}
