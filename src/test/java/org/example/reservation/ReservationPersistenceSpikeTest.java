package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C01 ENGINEERING SPIKE (A - Persistence).
 *
 * Question: does a Reservation, together with its Court and User, actually
 * survive a real round-trip through the database and the JPA mappings?
 *
 * Method: persist Court + User + Reservation, flush to the DB, then CLEAR the
 * persistence context (so nothing is served from the first-level cache) and
 * load the Reservation back by its generated id in a fresh unit of work.
 */
@DataJpaTest
class ReservationPersistenceSpikeTest {

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("Reservation survives a save -> flush -> clear -> reload round-trip")
    void reservationSurvivesRoundTrip() {
        // given: a court, a user and a DRAFT reservation
        Court court = em.persist(new Court(
                "Center Court", Court.CourtType.TENNIS, "Hall A",
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        AppUser user = em.persist(new AppUser("Test Player", "player@example.com"));

        LocalDateTime start = LocalDateTime.of(2026, 9, 20, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 20, 11, 0);
        Reservation saved = em.persist(new Reservation(court, user, start, end));
        Long id = saved.getId();

        // when: everything is written to the DB and the context is emptied
        em.flush();
        em.clear();

        // then: it can be loaded again with all fields intact
        Reservation loaded = em.find(Reservation.class, id);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getState()).isEqualTo(ReservationState.DRAFT);
        assertThat(loaded.getStartTime()).isEqualTo(start);
        assertThat(loaded.getEndTime()).isEqualTo(end);
        assertThat(loaded.getCreatedAt()).isNotNull();
        // relationships survived the round-trip
        assertThat(loaded.getCourt().getName()).isEqualTo("Center Court");
        assertThat(loaded.getCourt().getOpeningTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(loaded.getUser().getEmail()).isEqualTo("player@example.com");
    }
}
