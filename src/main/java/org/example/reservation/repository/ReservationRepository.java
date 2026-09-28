package org.example.reservation.repository;

import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * BR-01 + BR-02: reservations of a court, in any of the given states, whose
     * slot overlaps {@code [start, end)}.
     *
     * The overlap predicate is the half-open one from BR-01
     * ({@code a.start < b.end && b.start < a.end}), so intervals that merely
     * touch are NOT returned.
     */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.court.id = :courtId
              AND r.state IN :states
              AND r.startTime < :end
              AND :start < r.endTime
            """)
    List<Reservation> findOverlapping(@Param("courtId") Long courtId,
                                      @Param("states") Collection<ReservationState> states,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);

    /**
     * OP-07 / REQ-12: every request whose approval deadline has passed
     * ({@code approvalDeadline <= now}) and which is still awaiting a decision.
     */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.state = org.example.reservation.domain.ReservationState.PENDING_APPROVAL
              AND r.approvalDeadline <= :now
            """)
    List<Reservation> findDuePendingApprovals(@Param("now") LocalDateTime now);
}
