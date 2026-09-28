package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.example.reservation.service.AvailabilityResult;
import org.example.reservation.service.ReservationErrorCode;
import org.example.reservation.service.ReservationException;
import org.example.reservation.service.ReservationService;
import org.example.reservation.support.MutableClock;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes the verification examples of Specification Baseline v0.1
 * ({@code docs/c02-baseline-v0.1.md}).
 *
 * Every test is named with the V-id it executes, so the surefire output is the
 * evidence that links the specification to the running behaviour.
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BaselineV01VerificationTest {

    @Autowired private ReservationService service;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private ReservationRepository reservations;
    @Autowired private MutableClock clock;

    /** Fixture: an open, active court; a member owner; a second member; a staff user. */
    private Long courtId;
    private Long inactiveCourtId;
    private Long ownerId;
    private Long otherMemberId;
    private Long staffId;

    @BeforeEach
    void setUp() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);
        courtId = courts.save(new Court("Court 1", Court.CourtType.SQUASH, "Hall B",
                LocalTime.of(8, 0), LocalTime.of(22, 0))).getId();
        inactiveCourtId = courts.save(new Court("Court 9 (maintenance)", Court.CourtType.TENNIS, "Hall C",
                LocalTime.of(8, 0), LocalTime.of(22, 0), false)).getId();
        ownerId = users.save(new AppUser("Alice", unique("alice"), AppUser.Role.MEMBER)).getId();
        otherMemberId = users.save(new AppUser("Bob", unique("bob"), AppUser.Role.MEMBER)).getId();
        staffId = users.save(new AppUser("Dana (staff)", unique("dana"), AppUser.Role.STAFF)).getId();
    }

    // =================================================================
    // OP-01 Create Reservation
    // =================================================================

    @Nested
    @DisplayName("OP-01 Create Reservation")
    class Op01Create {

        @Test
        @DisplayName("V-01.1 success: valid interval on an active court -> DRAFT with an id")
        void v01_1() {
            Reservation r = service.create(ownerId, courtId, at(10, 0), at(11, 0));

            assertThat(r.getId()).isNotNull();
            assertThat(r.getState()).isEqualTo(ReservationState.DRAFT);
            assertThat(r.getUser().getId()).isEqualTo(ownerId);
            // OP-01 postcondition: no allocation is committed - availability is unchanged.
            assertThat(service.checkAvailability(courtId, at(10, 0), at(11, 0)).available()).isTrue();
        }

        @Test
        @DisplayName("V-01.2 create is a claim, not an allocation: a second DRAFT over a CONFIRMED slot is allowed")
        void v01_2() {
            confirmed(ownerId, at(10, 0), at(11, 0));

            Reservation r = service.create(otherMemberId, courtId, at(10, 0), at(11, 0));

            assertThat(r.getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-01.3 boundary: start == end is rejected INVALID_INTERVAL, nothing is created")
        void v01_3() {
            long before = reservations.count();

            assertThatThrownBy(() -> service.create(ownerId, courtId, at(10, 0), at(10, 0)))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_INTERVAL);

            assertThat(reservations.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("V-01.4 negative: unknown court -> COURT_NOT_FOUND")
        void v01_4() {
            assertThatThrownBy(() -> service.create(ownerId, 999_999L, at(10, 0), at(11, 0)))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.COURT_NOT_FOUND);
        }

        @Test
        @DisplayName("V-01.5 negative: unknown actor -> UNAUTHORIZED, nothing is created")
        void v01_5() {
            long before = reservations.count();

            assertThatThrownBy(() -> service.create(999_999L, courtId, at(10, 0), at(11, 0)))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED);

            assertThat(reservations.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("V-01.6 negative: inactive court -> COURT_INACTIVE")
        void v01_6() {
            assertThatThrownBy(() -> service.create(ownerId, inactiveCourtId, at(10, 0), at(11, 0)))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.COURT_INACTIVE);
        }
    }

    // =================================================================
    // OP-02 Check Availability
    // =================================================================

    @Nested
    @DisplayName("OP-02 Check Availability (fixture: CONFIRMED [10:00,11:00))")
    class Op02Availability {

        @BeforeEach
        void confirmedBooking() {
            confirmed(ownerId, at(10, 0), at(11, 0));
        }

        @Test
        @DisplayName("V-02.1 boundary success: [09:00,11:00) touching start -> AVAILABLE")
        void v02_1() {
            assertAvailable(at(9, 0), at(10, 0));
        }

        @Test
        @DisplayName("V-02.2 negative: [10:30,11:30) partial overlap -> UNAVAILABLE / CONFLICT")
        void v02_2() {
            assertUnavailable(at(10, 30), at(11, 30), AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("V-02.3 boundary success: [11:00,12:00) touching end -> AVAILABLE")
        void v02_3() {
            assertAvailable(at(11, 0), at(12, 0));
        }

        @Test
        @DisplayName("V-02.4 negative: identical interval -> UNAVAILABLE / CONFLICT")
        void v02_4() {
            assertUnavailable(at(10, 0), at(11, 0), AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("V-02.5 negative: [09:30,11:30) strictly containing -> UNAVAILABLE / CONFLICT")
        void v02_5() {
            assertUnavailable(at(9, 30), at(11, 30), AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("V-02.6 boundary: [07:00,08:30) before opening -> UNAVAILABLE / OUTSIDE_OPENING_HOURS")
        void v02_6() {
            assertUnavailable(at(7, 0), at(8, 30), AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS);
        }

        @Test
        @DisplayName("V-02.7 boundary: [21:30,22:30) past closing -> UNAVAILABLE / OUTSIDE_OPENING_HOURS")
        void v02_7() {
            assertUnavailable(at(21, 30), at(22, 30), AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS);
        }

        @Test
        @DisplayName("V-02.8 a DRAFT does not block: slot covered only by a DRAFT -> AVAILABLE")
        void v02_8() {
            service.create(ownerId, courtId, at(14, 0), at(15, 0));
            assertAvailable(at(14, 0), at(15, 0));
        }

        @Test
        @DisplayName("V-02.9 a CANCELLED reservation does not block -> AVAILABLE")
        void v02_9() {
            Reservation r = confirmed(ownerId, at(16, 0), at(17, 0));
            assertUnavailable(at(16, 0), at(17, 0), AvailabilityResult.Reason.CONFLICT);

            service.cancel(ownerId, r.getId());

            assertAvailable(at(16, 0), at(17, 0));
        }

        @Test
        @DisplayName("V-02.10 boundary: empty interval [10:00,10:00) -> UNAVAILABLE / INVALID_INTERVAL")
        void v02_10() {
            assertUnavailable(at(10, 0), at(10, 0), AvailabilityResult.Reason.INVALID_INTERVAL);
        }
    }

    // =================================================================
    // OP-03 Confirm Reservation
    // =================================================================

    @Nested
    @DisplayName("OP-03 Confirm Reservation")
    class Op03Confirm {

        @Test
        @DisplayName("V-03.1 success: no conflict -> CONFIRMED and the slot becomes UNAVAILABLE")
        void v03_1() {
            Reservation draft = service.create(ownerId, courtId, at(10, 0), at(11, 0));

            Reservation confirmed = service.confirm(ownerId, draft.getId());

            assertThat(confirmed.getState()).isEqualTo(ReservationState.CONFIRMED);
            assertUnavailable(at(10, 0), at(11, 0), AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("V-03.2 negative: overlapping confirm -> CONFLICT and the draft REMAINS DRAFT")
        void v03_2() {
            confirmed(ownerId, at(10, 0), at(11, 0));
            Reservation clashing = service.create(otherMemberId, courtId, at(10, 30), at(11, 30));

            assertThatThrownBy(() -> service.confirm(otherMemberId, clashing.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.CONFLICT);

            assertThat(reload(clashing).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-03.3 boundary success: [11:00,12:00) touching a CONFIRMED [10:00,11:00) -> CONFIRMED")
        void v03_3() {
            confirmed(ownerId, at(10, 0), at(11, 0));
            Reservation touching = service.create(ownerId, courtId, at(11, 0), at(12, 0));

            assertThat(service.confirm(ownerId, touching.getId()).getState())
                    .isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-03.4 boundary: [21:30,22:30) past closing -> OUTSIDE_OPENING_HOURS, remains DRAFT")
        void v03_4() {
            Reservation late = service.create(ownerId, courtId, at(21, 30), at(22, 30));

            assertThatThrownBy(() -> service.confirm(ownerId, late.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.OUTSIDE_OPENING_HOURS);

            assertThat(reload(late).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-03.5 negative: court deactivated after create -> COURT_INACTIVE, remains DRAFT")
        void v03_5() {
            Reservation draft = service.create(ownerId, courtId, at(10, 0), at(11, 0));
            Court court = courts.findById(courtId).orElseThrow();
            court.deactivate();
            courts.save(court);

            assertThatThrownBy(() -> service.confirm(ownerId, draft.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.COURT_INACTIVE);

            assertThat(reload(draft).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-03.6 negative: confirming an already CONFIRMED reservation -> INVALID_STATE")
        void v03_6() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.confirm(ownerId, r.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);
        }

        @Test
        @DisplayName("V-03.7 negative: CANCELLED can never become CONFIRMED -> INVALID_STATE")
        void v03_7() {
            Reservation r = service.create(ownerId, courtId, at(10, 0), at(11, 0));
            service.cancel(ownerId, r.getId());

            assertThatThrownBy(() -> service.confirm(ownerId, r.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);

            assertThat(reload(r).getState()).isEqualTo(ReservationState.CANCELLED);
        }

        @Test
        @DisplayName("V-03.8 negative: member B confirms member A's draft -> UNAUTHORIZED, remains DRAFT")
        void v03_8() {
            Reservation draft = service.create(ownerId, courtId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.confirm(otherMemberId, draft.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED);

            assertThat(reload(draft).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-03.9 REQ-04 concurrency: 8 parallel confirms of the same slot -> exactly 1 CONFIRMED")
        void v03_9() throws Exception {
            int n = 8;
            List<Long> draftIds = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                draftIds.add(service.create(ownerId, courtId, at(10, 0), at(11, 0)).getId());
            }

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger confirmedCount = new AtomicInteger();
            AtomicInteger rejectedCount = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                List<Future<Void>> futures = new java.util.ArrayList<>();
                for (Long id : draftIds) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        start.await();
                        try {
                            service.confirm(ownerId, id);
                            confirmedCount.incrementAndGet();
                        } catch (Exception e) {
                            rejectedCount.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<Void> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            long persistedConfirmed = draftIds.stream()
                    .map(id -> reservations.findById(id).orElseThrow())
                    .filter(r -> r.getState() == ReservationState.CONFIRMED)
                    .count();

            // BR-02 holds over the committed state: at most one CONFIRMED.
            assertThat(persistedConfirmed).isEqualTo(1L);
            assertThat(confirmedCount.get()).isEqualTo(1);
            assertThat(rejectedCount.get()).isEqualTo(n - 1);
            // every loser is still DRAFT
            assertThat(draftIds.stream()
                    .map(id -> reservations.findById(id).orElseThrow())
                    .filter(r -> r.getState() == ReservationState.DRAFT)
                    .count()).isEqualTo(n - 1L);
        }
    }

    // =================================================================
    // OP-04 Cancel Reservation
    // =================================================================

    @Nested
    @DisplayName("OP-04 Cancel Reservation")
    class Op04Cancel {

        @Test
        @DisplayName("V-04.1 success: cancel a DRAFT before start -> CANCELLED")
        void v04_1() {
            Reservation draft = service.create(ownerId, courtId, at(10, 0), at(11, 0));

            assertThat(service.cancel(ownerId, draft.getId()).getState())
                    .isEqualTo(ReservationState.CANCELLED);
        }

        @Test
        @DisplayName("V-04.2 success: cancelling a CONFIRMED reservation frees the slot")
        void v04_2() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));
            assertUnavailable(at(10, 0), at(11, 0), AvailabilityResult.Reason.CONFLICT);

            service.cancel(ownerId, r.getId());

            assertThat(reload(r).getState()).isEqualTo(ReservationState.CANCELLED);
            assertAvailable(at(10, 0), at(11, 0));
        }

        @Test
        @DisplayName("V-04.3 REQ-06 idempotency: a repeated cancel succeeds and writes nothing")
        void v04_3() {
            Reservation r = service.create(ownerId, courtId, at(10, 0), at(11, 0));
            service.cancel(ownerId, r.getId());
            java.time.Instant firstCancelledAt = reload(r).getCancelledAt();

            // the retry lands after the slot has already started - still a success
            clock.setTo(at(10, 5));
            Reservation again = service.cancel(ownerId, r.getId());

            assertThat(again.getState()).isEqualTo(ReservationState.CANCELLED);
            assertThat(reload(r).getCancelledAt()).isEqualTo(firstCancelledAt);
        }

        @Test
        @DisplayName("V-04.4 BOUNDARY: now == start is too late -> TOO_LATE_TO_CANCEL, remains CONFIRMED")
        void v04_4() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));
            clock.setTo(at(10, 0));

            assertThatThrownBy(() -> service.cancel(ownerId, r.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.TOO_LATE_TO_CANCEL);

            assertThat(reload(r).getState()).isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-04.5 negative: cancelling after start -> TOO_LATE_TO_CANCEL, remains CONFIRMED")
        void v04_5() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));
            clock.setTo(at(10, 30));

            assertThatThrownBy(() -> service.cancel(ownerId, r.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.TOO_LATE_TO_CANCEL);

            assertThat(reload(r).getState()).isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-04.6 negative: member B cancels member A's reservation -> UNAUTHORIZED, unchanged")
        void v04_6() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.cancel(otherMemberId, r.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED);

            assertThat(reload(r).getState()).isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-04.7 success: STAFF may cancel a member's reservation (BR-05)")
        void v04_7() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));

            assertThat(service.cancel(staffId, r.getId()).getState())
                    .isEqualTo(ReservationState.CANCELLED);
        }

        @Test
        @DisplayName("V-04.8 BR-03.4: cancel is not delete - the record is retained")
        void v04_8() {
            Reservation r = confirmed(ownerId, at(10, 0), at(11, 0));
            service.cancel(ownerId, r.getId());

            Reservation stored = reservations.findById(r.getId()).orElseThrow();
            assertThat(stored.getState()).isEqualTo(ReservationState.CANCELLED);
            assertThat(stored.getCancelledAt()).isNotNull();
        }
    }

    // =================================================================
    // helpers
    // =================================================================

    private Reservation confirmed(Long actorId, LocalDateTime start, LocalDateTime end) {
        Reservation draft = service.create(actorId, courtId, start, end);
        return service.confirm(actorId, draft.getId());
    }

    private Reservation reload(Reservation r) {
        return reservations.findById(r.getId()).orElseThrow();
    }

    private void assertAvailable(LocalDateTime start, LocalDateTime end) {
        AvailabilityResult result = service.checkAvailability(courtId, start, end);
        assertThat(result.available()).as("availability of [%s,%s)", start, end).isTrue();
        assertThat(result.reason()).isEqualTo(AvailabilityResult.Reason.AVAILABLE);
    }

    private void assertUnavailable(LocalDateTime start, LocalDateTime end,
                                   AvailabilityResult.Reason expected) {
        AvailabilityResult result = service.checkAvailability(courtId, start, end);
        assertThat(result.available()).as("availability of [%s,%s)", start, end).isFalse();
        assertThat(result.reason()).isEqualTo(expected);
    }

    /** Fixture day, matching the spec examples. */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute);
    }

    private static String unique(String prefix) {
        return prefix + "+" + System.nanoTime() + "@example.com";
    }
}
