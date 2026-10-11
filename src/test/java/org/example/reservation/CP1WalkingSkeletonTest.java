package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP1 walking skeleton (README "CP1 walking skeleton"), executed end to end over
 * real HTTP against the running application:
 *
 * POST /reservations -> validate -> persist -> return reservation ID -> automated check.
 *
 * Unlike the specification suites, which call the services directly, this test
 * goes through the embedded web server, JSON mapping, the controller, the
 * Reservation Lifecycle and the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
@DisplayName("CP1 walking skeleton - POST /reservations end to end")
class CP1WalkingSkeletonTest {

    @Autowired private TestRestTemplate http;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private ReservationRepository reservations;

    private Long courtId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        courtId = courts.save(new Court("Court CP1", Court.CourtType.TENNIS, "Hall A",
                LocalTime.of(8, 0), LocalTime.of(22, 0))).getId();
        memberId = users.save(new AppUser("Carol", "carol-" + System.nanoTime() + "@example.org",
                AppUser.Role.MEMBER)).getId();
    }

    @Test
    @DisplayName("CP1: valid request -> 201 + id -> DRAFT reservation persisted under that id")
    void validRequestIsPersistedAndItsIdReturned() {
        ResponseEntity<Map> response = post(memberId,
                "2026-09-21T10:00:00", "2026-09-21T11:00:00");

        // return reservation ID
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("state", "DRAFT");
        Long id = ((Number) response.getBody().get("id")).longValue();

        // persist: the returned id resolves to a stored row with the requested data
        Reservation stored = reservations.findById(id).orElseThrow();
        assertThat(stored.getState()).isEqualTo(ReservationState.DRAFT);
        assertThat(stored.getCourt().getId()).isEqualTo(courtId);
        assertThat(stored.getUser().getId()).isEqualTo(memberId);
        assertThat(stored.getStartTime()).hasToString("2026-09-21T10:00");
        assertThat(stored.getEndTime()).hasToString("2026-09-21T11:00");
    }

    @Test
    @DisplayName("CP1: invalid interval (start == end) -> 400 INVALID_INTERVAL, nothing persisted")
    void invalidRequestIsRejectedAndNothingPersisted() {
        long before = reservations.count();

        ResponseEntity<Map> response = post(memberId,
                "2026-09-21T10:00:00", "2026-09-21T10:00:00");

        // validate
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_INTERVAL");
        assertThat(reservations.count()).isEqualTo(before);
    }

    private ResponseEntity<Map> post(Long actorId, String start, String end) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor-Id", actorId.toString());
        Map<String, Object> body = Map.of("courtId", courtId, "start", start, "end", end);
        return http.exchange("/reservations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
