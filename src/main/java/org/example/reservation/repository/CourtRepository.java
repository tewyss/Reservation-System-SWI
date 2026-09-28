package org.example.reservation.repository;

import jakarta.persistence.LockModeType;
import org.example.reservation.domain.Court;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CourtRepository extends JpaRepository<Court, Long> {

    /**
     * REQ-04: load the court with an exclusive hold, so that the
     * "read overlapping reservations, then write CONFIRMED" sequence of OP-03
     * cannot be interleaved by another confirmation of the same court.
     *
     * This is ONE way to satisfy REQ-04, not the requirement itself - the
     * requirement states the outcome ("at most one reaches CONFIRMED") and the
     * placement of the guarantee is architecture driver AD-1, carried to C03.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Court c WHERE c.id = :id")
    Optional<Court> findByIdForUpdate(@Param("id") Long id);
}
