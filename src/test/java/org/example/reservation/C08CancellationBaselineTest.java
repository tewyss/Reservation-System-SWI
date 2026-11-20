package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.support.MutableClock;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;

import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C08 Controlled Change - TC-C08-01 / TC-C08-02 (docs/evidence-and-evolution.md, "C08 - Controlled Change").
 *
 * Executes the existing cancellation behaviour (OP-04, BR-03.2 of Baseline v0.1/v0.2: a
 * CONFIRMED reservation may be cancelled while {@code now < start}) through the real
 * integration boundary: embedded HTTP server -> controller -> Reservation Lifecycle -> JPA
 * -> database. The resulting state is NOT taken from the HTTP response: every check re-reads
 * the committed row with plain SQL in a separate connection (independent persisted-state oracle).
 *
 * Fixture: facility-local "now" = 2026-09-20 09:00 (TestClockConfig), exclusive court R1
 * open 08:00-22:00, member M1 owns booking B1.
 *
 * Each case is a {@link RepeatedTest} with 2 repetitions on fresh data: step 5 of the
 * test-case record ("repeat after reset -> same reproducible outcome").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
@DisplayName("C08 - cancellation baseline (TC-C08-01, TC-C08-02) over HTTP with persisted-state oracle")
class C08CancellationBaselineTest {

    private static final String DAY = "2026-09-20T";

    @Autowired private TestRestTemplate http;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Long courtId;
    private Long memberId;

    @BeforeEach
    void resetAndPrepare() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);                                   // now = 09:00
        courtId = courts.save(new Court("R1-" + System.nanoTime(), Court.CourtType.TENNIS, "Hall C08",
                LocalTime.of(8, 0), LocalTime.of(22, 0))).getId();
        memberId = users.save(new AppUser("M1", "m1-" + System.nanoTime() + "@example.org",
                AppUser.Role.MEMBER)).getId();
    }

    /**
     * TC-C08-01 - positive, preserved behaviour: a member cancels a CONFIRMED booking
     * 3 h before its start. Expected (BR-03.2, REQ-05): CANCELLED, record retained,
     * slot available again.
     */
    @RepeatedTest(value = 2, name = "TC-C08-01 run {currentRepetition}/{totalRepetitions}: cancel CONFIRMED [12:00,13:00) at 09:00 (3 h notice) -> CANCELLED, slot free")
    void tcC08_01_cancelWellAhead() {
        Long b1 = confirmedBooking("12:00", "13:00");
        step("TC-C08-01", 2, "persisted before cancel", row(b1));
        assertThat(row(b1).get("STATE")).isEqualTo("CONFIRMED");
        assertThat(available("12:00", "13:00")).isFalse();

        ResponseEntity<Map> cancel = cancel(b1);
        step("TC-C08-01", 3, "POST /reservations/" + b1 + "/cancel", cancel.getStatusCode() + " " + cancel.getBody());
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = row(b1);
        step("TC-C08-01", 4, "persisted after commit", after);
        assertThat(after.get("STATE")).isEqualTo("CANCELLED");
        assertThat(after.get("CANCELLED_AT")).isNotNull();                          // BR-03.4 retained
        assertThat(available("12:00", "13:00")).isTrue();                           // BR-02 no longer blocked
    }

    /**
     * TC-C08-02 - short-notice cancel: the member cancels a CONFIRMED booking 1 h before
     * start. Expected under the ACCEPTED rule of this baseline (BR-03.2: only
     * {@code now < start}): allowed -> CANCELLED. This is the observable limitation that
     * CR-01 (cancellation notice period, assumption A-5) asks to change.
     */
    @RepeatedTest(value = 2, name = "TC-C08-02 run {currentRepetition}/{totalRepetitions}: cancel CONFIRMED [10:00,11:00) at 09:00 (1 h notice) -> baseline BR-03.2 allows it")
    void tcC08_02_cancelAtShortNotice() {
        Long b1 = confirmedBooking("10:00", "11:00");
        step("TC-C08-02", 2, "persisted before cancel", row(b1));
        assertThat(row(b1).get("STATE")).isEqualTo("CONFIRMED");

        ResponseEntity<Map> cancel = cancel(b1);
        step("TC-C08-02", 3, "POST /reservations/" + b1 + "/cancel", cancel.getStatusCode() + " " + cancel.getBody());
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = row(b1);
        step("TC-C08-02", 4, "persisted after commit", after);
        assertThat(after.get("STATE")).isEqualTo("CANCELLED");
        assertThat(available("10:00", "11:00")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** Step 1: create + confirm through HTTP, so the fixture uses the real write path. */
    private Long confirmedBooking(String start, String end) {
        HttpHeaders headers = actor();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("courtId", courtId, "start", DAY + start, "end", DAY + end);
        ResponseEntity<Map> created = http.exchange("/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Map> confirmed = http.exchange("/reservations/" + id + "/confirm", HttpMethod.POST,
                new HttpEntity<>(actor()), Map.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        step("setup", 1, "B1 created+confirmed [" + start + "," + end + ") now=" + clock.instant(), confirmed.getBody());
        return id;
    }

    private ResponseEntity<Map> cancel(Long id) {
        return http.exchange("/reservations/" + id + "/cancel", HttpMethod.POST, new HttpEntity<>(actor()), Map.class);
    }

    private boolean available(String start, String end) {
        Map<?, ?> body = http.getForObject("/reservations/availability?courtId={c}&start={s}&end={e}",
                Map.class, courtId, DAY + start, DAY + end);
        return Boolean.TRUE.equals(body.get("available"));
    }

    /** Independent oracle: the committed row, read with plain SQL - not through JPA or the response. */
    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("select id, state, start_time, end_time, cancelled_at from reservation where id = ?", id);
    }

    private HttpHeaders actor() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Actor-Id", memberId.toString());
        return headers;
    }

    private static void step(String tc, int n, String what, Object observed) {
        System.out.printf("[%s] step %d %s -> %s%n", tc, n, what, observed);
    }
}
