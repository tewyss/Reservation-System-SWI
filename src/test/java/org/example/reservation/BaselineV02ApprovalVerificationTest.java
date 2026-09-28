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
import java.util.ArrayList;
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
 * Executes the verification examples added by the C02 change
 * ({@code docs/c02-change-v0.2-approval.md}).
 *
 * The v0.1 examples are re-executed unchanged by
 * {@link BaselineV01VerificationTest} as the change's regression evidence - the
 * impact analysis claims BR-01, BR-02 and OP-01 are unaffected, and that claim is
 * only credible if those examples still produce the same answers.
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BaselineV02ApprovalVerificationTest {

    @Autowired private ReservationService service;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private ReservationRepository reservations;
    @Autowired private MutableClock clock;

    /** A court that requires approval (BR-08) and a self-service one, for contrast. */
    private Long gatedCourtId;
    private Long openCourtId;
    private Long memberId;
    private Long otherMemberId;
    private Long approverId;
    /** A STAFF user who also books for themselves - the separation-of-duty case. */
    private Long staffBookerId;

    @BeforeEach
    void setUp() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);
        // OP-07 sweeps the WHOLE system, so each example starts from a known set.
        reservations.deleteAll();
        gatedCourtId = courts.save(new Court("Centre Court (approval)", Court.CourtType.TENNIS,
                "Hall A", LocalTime.of(8, 0), LocalTime.of(22, 0), true, true)).getId();
        openCourtId = courts.save(new Court("Court 2 (self-service)", Court.CourtType.SQUASH,
                "Hall B", LocalTime.of(8, 0), LocalTime.of(22, 0), true, false)).getId();
        memberId = users.save(new AppUser("Alice", unique("alice"), AppUser.Role.MEMBER)).getId();
        otherMemberId = users.save(new AppUser("Bob", unique("bob"), AppUser.Role.MEMBER)).getId();
        approverId = users.save(new AppUser("Dana (manager)", unique("dana"), AppUser.Role.STAFF)).getId();
        staffBookerId = users.save(new AppUser("Erik (staff)", unique("erik"), AppUser.Role.STAFF)).getId();
    }

    // =================================================================
    // OP-03 Confirm - changed by the approval gate
    // =================================================================

    @Nested
    @DisplayName("OP-03 Confirm - the approval branch (REQ-09)")
    class Op03Changed {

        @Test
        @DisplayName("V-03.10 success: confirm on a GATED court -> PENDING_APPROVAL, nothing allocated")
        void v03_10() {
            Reservation draft = service.create(memberId, gatedCourtId, at(10, 0), at(11, 0));

            Reservation submitted = service.confirm(memberId, draft.getId());

            assertThat(submitted.getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
            assertThat(submitted.getApprovalDeadline()).isNotNull();
            // decision D-2: a pending request does not block the slot
            AvailabilityResult result = service.checkAvailability(gatedCourtId, at(10, 0), at(11, 0));
            assertThat(result.available()).isTrue();
        }

        @Test
        @DisplayName("V-03.11 regression: confirm on a SELF-SERVICE court -> CONFIRMED (v0.1 preserved)")
        void v03_11() {
            Reservation draft = service.create(memberId, openCourtId, at(10, 0), at(11, 0));

            assertThat(service.confirm(memberId, draft.getId()).getState())
                    .isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-03.12 negative: an impossible request on a gated court never reaches an approver")
        void v03_12() {
            // make [10:00,11:00) CONFIRMED on the gated court via an approved request
            Reservation first = service.create(memberId, gatedCourtId, at(10, 0), at(11, 0));
            service.confirm(memberId, first.getId());
            service.approve(approverId, first.getId());

            Reservation clashing = service.create(otherMemberId, gatedCourtId, at(10, 30), at(11, 30));

            assertThatThrownBy(() -> service.confirm(otherMemberId, clashing.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.CONFLICT);

            assertThat(reload(clashing).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("BR-10 boundary: the approval deadline never exceeds the reservation start")
        void deadlineNeverAfterStart() {
            // window is 24 h, the slot starts in ~1 h -> the deadline must be clamped to start
            Reservation draft = service.create(memberId, gatedCourtId, at(10, 0), at(11, 0));

            Reservation submitted = service.confirm(memberId, draft.getId());

            assertThat(submitted.getApprovalDeadline()).isEqualTo(at(10, 0));
        }
    }

    // =================================================================
    // OP-02 Availability - extended by REQ-13
    // =================================================================

    @Nested
    @DisplayName("OP-02 Check Availability - approval disclosure (REQ-13)")
    class Op02Extended {

        @Test
        @DisplayName("V-02.11 two pending requests do NOT block, but ARE disclosed")
        void v02_11() {
            submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            submitted(otherMemberId, gatedCourtId, at(10, 0), at(11, 0));

            AvailabilityResult result = service.checkAvailability(gatedCourtId, at(10, 0), at(11, 0));

            assertThat(result.available()).isTrue();                        // BR-02 unchanged (D-2)
            assertThat(result.reason()).isEqualTo(AvailabilityResult.Reason.AVAILABLE);
            assertThat(result.approvalRequired()).isTrue();                 // REQ-13
            assertThat(result.pendingApprovalCount()).isEqualTo(2);         // REQ-13
        }

        @Test
        @DisplayName("REQ-13: a self-service court reports approvalRequired = false and no pending")
        void selfServiceDisclosure() {
            AvailabilityResult result = service.checkAvailability(openCourtId, at(10, 0), at(11, 0));

            assertThat(result.available()).isTrue();
            assertThat(result.approvalRequired()).isFalse();
            assertThat(result.pendingApprovalCount()).isZero();
        }
    }

    // =================================================================
    // OP-05 Approve Reservation
    // =================================================================

    @Nested
    @DisplayName("OP-05 Approve Reservation")
    class Op05Approve {

        @Test
        @DisplayName("V-05.1 success: staff approver -> CONFIRMED, decision recorded, slot now blocks")
        void v05_1() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            Reservation approved = service.approve(approverId, pending.getId());

            assertThat(approved.getState()).isEqualTo(ReservationState.CONFIRMED);
            assertThat(approved.getDecidedBy().getId()).isEqualTo(approverId);
            assertThat(approved.getDecidedAt()).isNotNull();
            AvailabilityResult after = service.checkAvailability(gatedCourtId, at(10, 0), at(11, 0));
            assertThat(after.available()).isFalse();
            assertThat(after.reason()).isEqualTo(AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("V-05.2 negative: a MEMBER cannot approve -> UNAUTHORIZED, stays PENDING_APPROVAL")
        void v05_2() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.approve(otherMemberId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("V-05.3 BR-09.2 separation of duty: STAFF cannot approve their OWN reservation")
        void v05_3() {
            Reservation pending = submitted(staffBookerId, gatedCourtId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.approve(staffBookerId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.SELF_APPROVAL);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
            // ... but a different approver may decide it
            assertThat(service.approve(approverId, pending.getId()).getState())
                    .isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("V-05.4 BOUNDARY now == approvalDeadline: APPROVAL_EXPIRED and state becomes EXPIRED")
        void v05_4() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            assertThat(pending.getApprovalDeadline()).isEqualTo(at(10, 0));

            clock.setTo(at(10, 0));     // exactly on the boundary

            assertThatThrownBy(() -> service.approve(approverId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.APPROVAL_EXPIRED);

            // REQ-12 / outcome E7: lazy expiry is a real state change
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.EXPIRED);
        }

        @Test
        @DisplayName("V-05.5 negative: the slot was taken while waiting -> CONFLICT, stays PENDING_APPROVAL")
        void v05_5() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            // another request for the same slot gets approved first
            Reservation rival = submitted(otherMemberId, gatedCourtId, at(10, 30), at(11, 30));
            service.approve(approverId, rival.getId());

            assertThatThrownBy(() -> service.approve(approverId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.CONFLICT);

            // a failed approval is not a decision (outcome E10)
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("V-05.6 negative: court deactivated while waiting -> COURT_INACTIVE, request survives")
        void v05_6() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            Court court = courts.findById(gatedCourtId).orElseThrow();
            court.deactivate();
            courts.save(court);

            assertThatThrownBy(() -> service.approve(approverId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.COURT_INACTIVE);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("V-05.7 negative: approving a DRAFT (never submitted) -> INVALID_STATE")
        void v05_7() {
            Reservation draft = service.create(memberId, gatedCourtId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.approve(approverId, draft.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);

            assertThat(reload(draft).getState()).isEqualTo(ReservationState.DRAFT);
        }

        @Test
        @DisplayName("V-05.8 negative: approving an already CONFIRMED reservation -> INVALID_STATE")
        void v05_8() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            service.approve(approverId, pending.getId());

            assertThatThrownBy(() -> service.approve(approverId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);
        }

        @Test
        @DisplayName("V-05.9 REQ-15 concurrency: 2 conflicting approvals in parallel -> exactly 1 CONFIRMED")
        void v05_9() throws Exception {
            Reservation a = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            Reservation b = submitted(otherMemberId, gatedCourtId, at(10, 30), at(11, 30));

            List<Long> ids = List.of(a.getId(), b.getId());
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger approved = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Void>> futures = new ArrayList<>();
                for (Long id : ids) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        start.await();
                        try {
                            service.approve(approverId, id);
                            approved.incrementAndGet();
                        } catch (Exception e) {
                            rejected.incrementAndGet();
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

            long confirmed = ids.stream()
                    .map(id -> reservations.findById(id).orElseThrow())
                    .filter(r -> r.getState() == ReservationState.CONFIRMED)
                    .count();
            long stillPending = ids.stream()
                    .map(id -> reservations.findById(id).orElseThrow())
                    .filter(r -> r.getState() == ReservationState.PENDING_APPROVAL)
                    .count();

            // BR-02 holds across BOTH allocating operations (REQ-15)
            assertThat(confirmed).isEqualTo(1L);
            assertThat(stillPending).isEqualTo(1L);
            assertThat(approved.get()).isEqualTo(1);
            assertThat(rejected.get()).isEqualTo(1);
        }
    }

    // =================================================================
    // OP-06 Reject Reservation
    // =================================================================

    @Nested
    @DisplayName("OP-06 Reject Reservation")
    class Op06Reject {

        @Test
        @DisplayName("V-06.1 success: reject with a reason -> REJECTED, availability unchanged")
        void v06_1() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            Reservation rejected = service.reject(approverId, pending.getId(), "Court booked for a tournament");

            assertThat(rejected.getState()).isEqualTo(ReservationState.REJECTED);
            assertThat(rejected.getDecidedBy().getId()).isEqualTo(approverId);
            assertThat(rejected.getDecisionReason()).isEqualTo("Court booked for a tournament");
            // a rejection allocates nothing
            assertThat(service.checkAvailability(gatedCourtId, at(10, 0), at(11, 0)).available()).isTrue();
        }

        @Test
        @DisplayName("V-06.2 BR-11 terminality: a REJECTED request cannot be approved")
        void v06_2() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            service.reject(approverId, pending.getId(), "no");

            assertThatThrownBy(() -> service.approve(approverId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.REJECTED);
        }

        @Test
        @DisplayName("V-06.3 negative: a MEMBER cannot reject -> UNAUTHORIZED, stays PENDING_APPROVAL")
        void v06_3() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            assertThatThrownBy(() -> service.reject(otherMemberId, pending.getId(), "nope"))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("V-06.4 negative: a CONFIRMED reservation is undone by Cancel, not by a late refusal")
        void v06_4() {
            Reservation confirmed = service.create(memberId, openCourtId, at(10, 0), at(11, 0));
            service.confirm(memberId, confirmed.getId());

            assertThatThrownBy(() -> service.reject(approverId, confirmed.getId(), "too late"))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);

            assertThat(reload(confirmed).getState()).isEqualTo(ReservationState.CONFIRMED);
        }

        @Test
        @DisplayName("F5: a refusal is still recordable after the approval deadline has passed")
        void f5_rejectAfterDeadline() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            clock.setTo(at(10, 30));    // past the deadline

            assertThat(service.reject(approverId, pending.getId(), "late refusal").getState())
                    .isEqualTo(ReservationState.REJECTED);
        }
    }

    // =================================================================
    // OP-07 Expire Pending Approvals
    // =================================================================

    @Nested
    @DisplayName("OP-07 Expire Pending Approvals")
    class Op07Expire {

        @Test
        @DisplayName("V-07.1 success: an overdue request is expired; availability is unchanged")
        void v07_1() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            clock.setTo(at(10, 30));

            int expired = service.expirePendingApprovals();

            assertThat(expired).isEqualTo(1);
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.EXPIRED);
            // it never blocked, so expiring it cannot change availability
            assertThat(service.checkAvailability(gatedCourtId, at(10, 0), at(11, 0)).available()).isTrue();
        }

        @Test
        @DisplayName("V-07.2 BOUNDARY now = deadline - 1 min: stays PENDING_APPROVAL")
        void v07_2() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            clock.setTo(at(9, 59));

            assertThat(service.expirePendingApprovals()).isZero();
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("V-07.3 BOUNDARY now == deadline exactly: EXPIRED (now >= deadline, BR-10)")
        void v07_3() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            clock.setTo(at(10, 0));

            assertThat(service.expirePendingApprovals()).isEqualTo(1);
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.EXPIRED);
        }

        @Test
        @DisplayName("V-07.4 G1: the sweep is a no-op when nothing is due, and is idempotent")
        void v07_4() {
            submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            assertThat(service.expirePendingApprovals()).isZero();
            assertThat(service.expirePendingApprovals()).isZero();
        }

        @Test
        @DisplayName("G3: a request cancelled before the sweep sees it is skipped - cancel wins")
        void g3_cancelBeatsSweep() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            service.cancel(memberId, pending.getId());      // now = 09:00, before start

            clock.setTo(at(10, 30));

            assertThat(service.expirePendingApprovals()).isZero();
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.CANCELLED);
        }
    }

    // =================================================================
    // OP-04 Cancel - amended by the change
    // =================================================================

    @Nested
    @DisplayName("OP-04 Cancel - amended cancellable states (BR-03.1)")
    class Op04Changed {

        @Test
        @DisplayName("V-04.9 success: a member may withdraw a request awaiting approval")
        void v04_9() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));

            assertThat(service.cancel(memberId, pending.getId()).getState())
                    .isEqualTo(ReservationState.CANCELLED);
        }

        @Test
        @DisplayName("V-04.10 D7: a REJECTED reservation cannot be cancelled -> INVALID_STATE")
        void v04_10() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            service.reject(approverId, pending.getId(), "no");

            assertThatThrownBy(() -> service.cancel(memberId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);

            assertThat(reload(pending).getState()).isEqualTo(ReservationState.REJECTED);
        }

        @Test
        @DisplayName("D7: an EXPIRED reservation cannot be cancelled either -> INVALID_STATE")
        void d7_expiredNotCancellable() {
            Reservation pending = submitted(memberId, gatedCourtId, at(10, 0), at(11, 0));
            clock.setTo(at(9, 59, 59));
            service.expirePendingApprovals();               // not yet due
            clock.setTo(at(10, 0));
            service.expirePendingApprovals();               // now due
            assertThat(reload(pending).getState()).isEqualTo(ReservationState.EXPIRED);

            clock.setTo(TestClockConfig.FIXTURE_NOW);       // back before start, so only state blocks it
            assertThatThrownBy(() -> service.cancel(memberId, pending.getId()))
                    .isInstanceOf(ReservationException.class)
                    .extracting(e -> ((ReservationException) e).getCode())
                    .isEqualTo(ReservationErrorCode.INVALID_STATE);
        }
    }

    // =================================================================
    // helpers
    // =================================================================

    /** Create + confirm on a gated court, i.e. a request awaiting a decision. */
    private Reservation submitted(Long ownerId, Long courtId, LocalDateTime start, LocalDateTime end) {
        Reservation draft = service.create(ownerId, courtId, start, end);
        return service.confirm(ownerId, draft.getId());
    }

    private Reservation reload(Reservation r) {
        return reservations.findById(r.getId()).orElseThrow();
    }

    /** Fixture day, matching the spec examples. */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute);
    }

    private static LocalDateTime at(int hour, int minute, int second) {
        return LocalDateTime.of(2026, 9, 20, hour, minute, second);
    }

    private static String unique(String prefix) {
        return prefix + "+" + System.nanoTime() + "@example.com";
    }
}
