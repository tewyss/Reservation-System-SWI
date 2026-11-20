package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.support.MutableClock;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C09 - CR-01 cancellation notice period (BR-03.6), acceptance conditions AC-01..AC-05 of the
 * C08 Change Card (docs/intent-and-change.md), executed over real HTTP against the running
 * application. Every verdict reads the committed row with plain SQL (independent oracle).
 *
 * Fixture: booking {@code [2026-09-20 12:00, 13:00)} on exclusive court R1 (08:00-22:00),
 * notice period N = 2 h (src/test/resources/application.properties) -> notice deadline 10:00.
 * The expected values below were written from the Change Card BEFORE the first run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
@DisplayName("C09 CR-01 - cancellation notice period (AC-01..AC-04) over HTTP with persisted-state oracle")
class C09CancellationNoticeTest {

    static final String DAY = "2026-09-20T";

    @Autowired TestRestTemplate http;
    @Autowired CourtRepository courts;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    Long courtId;
    Long gatedCourtId;
    Long memberId;
    Long staffId;

    @BeforeEach
    void resetAndPrepare() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);                                    // 09:00
        courtId = courts.save(new Court("R1-" + System.nanoTime(), Court.CourtType.TENNIS, "Hall C09",
                LocalTime.of(8, 0), LocalTime.of(22, 0))).getId();
        gatedCourtId = courts.save(new Court("G1-" + System.nanoTime(), Court.CourtType.TENNIS, "Hall C09",
                LocalTime.of(8, 0), LocalTime.of(22, 0), true, true)).getId();
        memberId = users.save(new AppUser("M1", "m1-" + System.nanoTime() + "@example.org",
                AppUser.Role.MEMBER)).getId();
        staffId = users.save(new AppUser("S1", "s1-" + System.nanoTime() + "@example.org",
                AppUser.Role.STAFF)).getId();
    }

    @Test
    @DisplayName("TC-CR01-01 AC-01: member cancels CONFIRMED at 09:59:59 (1 s before the notice deadline) -> CANCELLED, slot free")
    void ac01_justBeforeNoticeDeadline() {
        Long b = confirmed(courtId);
        clock.setTo(at("09:59:59"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(b)).isEqualTo("CANCELLED");
        assertThat(cancelledAt(b)).isNotNull();
        assertThat(available(courtId)).isTrue();
        log("TC-CR01-01", r, b);
    }

    @Test
    @DisplayName("TC-CR01-02 AC-03 BOUNDARY: member cancels CONFIRMED at exactly 10:00:00 (= start - N) -> TOO_LATE_TO_CANCEL, stays CONFIRMED")
    void ac03_exactlyAtNoticeDeadline() {
        Long b = confirmed(courtId);
        clock.setTo(at("10:00:00"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertRefusedAndStillConfirmed(r, b);
        log("TC-CR01-02", r, b);
    }

    @Test
    @DisplayName("TC-CR01-03 AC-02: member cancels CONFIRMED at 11:00 (1 h before start) -> TOO_LATE_TO_CANCEL, stays CONFIRMED, slot still blocked")
    void ac02_insideNoticePeriod() {
        Long b = confirmed(courtId);
        clock.setTo(at("11:00:00"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertRefusedAndStillConfirmed(r, b);
        assertThat((String) r.getBody().get("message")).contains("PT2H").contains("2026-09-20T10:00");
        log("TC-CR01-03", r, b);
    }

    @Test
    @DisplayName("TC-CR01-04 AC-04: STAFF cancels a member's CONFIRMED at 11:00 -> CANCELLED (exempt)")
    void ac04_staffExempt() {
        Long b = confirmed(courtId);
        clock.setTo(at("11:00:00"));

        ResponseEntity<Map> r = cancel(staffId, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(b)).isEqualTo("CANCELLED");
        log("TC-CR01-04", r, b);
    }

    @Test
    @DisplayName("TC-CR01-05 AC-04: member cancels own DRAFT at 11:00 -> CANCELLED (no court held)")
    void ac04_draftExempt() {
        Long b = created(courtId);
        clock.setTo(at("11:00:00"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(b)).isEqualTo("CANCELLED");
        log("TC-CR01-05", r, b);
    }

    @Test
    @DisplayName("TC-CR01-06 AC-04: member withdraws own PENDING_APPROVAL at 11:00 -> CANCELLED (no court held)")
    void ac04_pendingExempt() {
        Long b = created(gatedCourtId);
        assertThat(post("/reservations/" + b + "/confirm", memberId).getBody()).containsEntry("state", "PENDING_APPROVAL");
        clock.setTo(at("11:00:00"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(b)).isEqualTo("CANCELLED");
        log("TC-CR01-06", r, b);
    }

    @Test
    @DisplayName("TC-CR01-07 AC-04: STAFF at now == start is still too late (BR-03.2 unchanged) -> stays CONFIRMED")
    void ac04_startBoundaryUnchangedForStaff() {
        Long b = confirmed(courtId);
        clock.setTo(at("12:00:00"));

        ResponseEntity<Map> r = cancel(staffId, b);

        assertRefusedAndStillConfirmed(r, b);
        log("TC-CR01-07", r, b);
    }

    @Test
    @DisplayName("TC-CR01-08 AC-04: repeated cancel of a CANCELLED booking inside the notice period still succeeds (REQ-06)")
    void ac04_idempotentRepeat() {
        Long b = confirmed(courtId);
        cancel(memberId, b);                                                          // 09:00, valid
        Object firstCancelledAt = cancelledAt(b);
        clock.setTo(at("11:00:00"));

        ResponseEntity<Map> r = cancel(memberId, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(b)).isEqualTo("CANCELLED");
        assertThat(cancelledAt(b)).isEqualTo(firstCancelledAt);
        log("TC-CR01-08", r, b);
    }

    @Test
    @DisplayName("TC-CR01-09 AC-04 BR-02: refused late cancel keeps the court held -> an overlapping booking is still refused CONFLICT")
    void ac04_br02StillHolds() {
        Long b = confirmed(courtId);
        clock.setTo(at("11:00:00"));
        cancel(memberId, b);                                                          // refused

        Long other = users.save(new AppUser("M2", "m2-" + System.nanoTime() + "@example.org",
                AppUser.Role.MEMBER)).getId();
        Long b2 = created(courtId, other, "12:30", "13:30");
        ResponseEntity<Map> r = post("/reservations/" + b2 + "/confirm", other);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "CONFLICT");
        Integer confirmedOverlapping = jdbc.queryForObject(
                "select count(*) from reservation where court_id = ? and state = 'CONFIRMED'"
                        + " and start_time < ? and end_time > ?",
                Integer.class, courtId, at("13:00:00"), at("12:00:00"));
        assertThat(confirmedOverlapping).isEqualTo(1);
        log("TC-CR01-09", r, b2);
    }

    /**
     * AC-05: PT0S must reproduce the before-baseline exactly (TC-C08-02 old oracle).
     * Its own application context (different property), so it uses only its own beans.
     */
    @Nested
    @TestPropertySource(properties = "reservation.cancellation.notice-period=PT0S")
    @DisplayName("AC-05 configuration")
    class NoticePeriodZero {

        @Autowired TestRestTemplate http0;
        @Autowired MutableClock clock0;
        @Autowired JdbcTemplate jdbc0;
        @Autowired CourtRepository courts0;
        @Autowired AppUserRepository users0;

        @Test
        @DisplayName("TC-CR01-10 AC-05: with notice-period=PT0S a member cancel 1 h before start succeeds again (old behaviour)")
        void ac05_zeroIsOldBehaviour() {
            clock0.setTo(TestClockConfig.FIXTURE_NOW);
            Long court = courts0.save(new Court("Z1-" + System.nanoTime(), Court.CourtType.TENNIS, "Hall C09",
                    LocalTime.of(8, 0), LocalTime.of(22, 0))).getId();
            Long member = users0.save(new AppUser("M0", "m0-" + System.nanoTime() + "@example.org",
                    AppUser.Role.MEMBER)).getId();
            HttpHeaders json = actor(member);
            json.setContentType(MediaType.APPLICATION_JSON);
            Long b = ((Number) http0.exchange("/reservations", HttpMethod.POST, new HttpEntity<>(
                    Map.of("courtId", court, "start", DAY + "12:00", "end", DAY + "13:00"), json), Map.class)
                    .getBody().get("id")).longValue();
            assertThat(http0.exchange("/reservations/" + b + "/confirm", HttpMethod.POST,
                    new HttpEntity<>(actor(member)), Map.class).getBody()).containsEntry("state", "CONFIRMED");
            clock0.setTo(at("11:00:00"));

            ResponseEntity<Map> r = http0.exchange("/reservations/" + b + "/cancel", HttpMethod.POST,
                    new HttpEntity<>(actor(member)), Map.class);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc0.queryForObject("select state from reservation where id = ?", String.class, b))
                    .isEqualTo("CANCELLED");
            System.out.printf("[TC-CR01-10] notice-period=PT0S now=11:00 -> HTTP %s %s%n", r.getStatusCode().value(), r.getBody());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertRefusedAndStillConfirmed(ResponseEntity<Map> r, Long b) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "TOO_LATE_TO_CANCEL");
        assertThat(state(b)).isEqualTo("CONFIRMED");
        assertThat(cancelledAt(b)).isNull();
        assertThat(available(courtId)).isFalse();
    }

    Long created(Long court) {
        return created(court, memberId, "12:00", "13:00");
    }

    Long created(Long court, Long owner, String start, String end) {
        HttpHeaders headers = actor(owner);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("courtId", court, "start", DAY + start, "end", DAY + end);
        ResponseEntity<Map> r = http.exchange("/reservations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) r.getBody().get("id")).longValue();
    }

    Long confirmed(Long court) {
        TestRestTemplate client = http;
        HttpHeaders headers = actor(memberId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("courtId", court, "start", DAY + "12:00", "end", DAY + "13:00");
        ResponseEntity<Map> c = client.exchange("/reservations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Long id = ((Number) c.getBody().get("id")).longValue();
        ResponseEntity<Map> r = client.exchange("/reservations/" + id + "/confirm", HttpMethod.POST,
                new HttpEntity<>(actor(memberId)), Map.class);
        assertThat(r.getBody()).containsEntry("state", "CONFIRMED");
        return id;
    }

    ResponseEntity<Map> cancel(Long actorId, Long id) {
        return post("/reservations/" + id + "/cancel", actorId);
    }

    ResponseEntity<Map> post(String path, Long actorId) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(actor(actorId)), Map.class);
    }

    boolean available(Long court) {
        Map<?, ?> body = http.getForObject("/reservations/availability?courtId={c}&start={s}&end={e}",
                Map.class, court, DAY + "12:00", DAY + "13:00");
        return Boolean.TRUE.equals(body.get("available"));
    }

    String state(Long id) {
        return jdbc.queryForObject("select state from reservation where id = ?", String.class, id);
    }

    Object cancelledAt(Long id) {
        return jdbc.queryForMap("select cancelled_at from reservation where id = ?", id).get("CANCELLED_AT");
    }

    static HttpHeaders actor(Long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Actor-Id", id.toString());
        return headers;
    }

    static LocalDateTime at(String hhmmss) {
        return LocalDateTime.parse(DAY + hhmmss);
    }

    void log(String tc, ResponseEntity<Map> r, Long id) {
        System.out.printf("[%s] now=%s -> HTTP %s %s | committed row: %s%n", tc,
                LocalDateTime.ofInstant(clock.instant(), clock.getZone()), r.getStatusCode().value(), r.getBody(),
                jdbc.queryForMap("select id, state, cancelled_at from reservation where id = ?", id));
    }
}
