package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.example.reservation.service.NotificationService;
import org.example.reservation.service.ApprovalWorkflow;
import org.example.reservation.service.ReservationService;
import org.example.reservation.support.FlushGate;
import org.example.reservation.support.MutableClock;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C03 - the single-reservation lifecycle race found while mapping the AS-IS
 * implementation (docs/c03-architecture.md, Part A finding F-A4).
 *
 * BR-02 is about two DIFFERENT reservations competing for one court; the court
 * hold (REQ-04 / REQ-15) covers that. These examples are about two operations
 * deciding the SAME reservation at once: an approver approving while another
 * approver rejects it, or while its owner withdraws it. The C02 REQ-11 gate claims
 * "the loser fails on the source-state guard" - which is only true if the guard
 * and the write are atomic. {@link FlushGate} parks the approval after it has
 * checked the source state and before its write lands, so the interleaving is
 * forced rather than hoped for.
 *
 * Expected outcome (statechart: exactly one edge leaves PENDING_APPROVAL):
 * exactly one of the two operations succeeds, the persisted state is that
 * operation's target state, and the owner is never told an outcome that did not
 * commit.
 */
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.interceptor=org.example.reservation.support.FlushGate")
@Import({TestClockConfig.class, C03LifecycleConcurrencyTest.RecordingNotifications.class})
class C03LifecycleConcurrencyTest {

    @Autowired private ReservationService service;
    @Autowired private ApprovalWorkflow approvals;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private ReservationRepository reservations;
    @Autowired private MutableClock clock;
    @Autowired private RecordingNotifier notifier;

    private Long gatedCourtId;
    private Long memberId;
    private Long approverId;
    private Long secondApproverId;

    @BeforeEach
    void setUp() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);
        reservations.deleteAll();
        notifier.sent.clear();
        gatedCourtId = courts.save(new Court("Centre Court (approval)", Court.CourtType.TENNIS,
                "Hall A", LocalTime.of(8, 0), LocalTime.of(22, 0), true, true)).getId();
        memberId = users.save(new AppUser("Alice", unique("alice"), AppUser.Role.MEMBER)).getId();
        approverId = users.save(new AppUser("Dana", unique("dana"), AppUser.Role.STAFF)).getId();
        secondApproverId = users.save(new AppUser("Petr", unique("petr"), AppUser.Role.STAFF)).getId();
    }

    @AfterEach
    void tearDown() {
        FlushGate.disarm();
    }

    @Test
    @DisplayName("V-06.5 approve vs reject on the SAME request: exactly one decision stands")
    void v06_5() throws Exception {
        Reservation pending = submitted();

        Race race = race(pending.getId(),
                () -> approvals.reject(secondApproverId, pending.getId(), "court needed").getState());

        assertExactlyOneDecisionStands(pending.getId(), race, ReservationState.REJECTED);
    }

    @Test
    @DisplayName("V-04.11 approve vs owner's cancel on the SAME request: exactly one outcome stands")
    void v04_11() throws Exception {
        Reservation pending = submitted();

        Race race = race(pending.getId(),
                () -> service.cancel(memberId, pending.getId()).getState());

        assertExactlyOneDecisionStands(pending.getId(), race, ReservationState.CANCELLED);
    }

    // -----------------------------------------------------------------

    /** Approve is parked between "state is PENDING_APPROVAL" and its write; the rival runs meanwhile. */
    private Race race(Long reservationId, Supplier<ReservationState> rival) throws Exception {
        FlushGate.arm(ReservationState.CONFIRMED);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Outcome> approving = pool.submit(() -> attempt(
                    () -> approvals.approve(approverId, reservationId).getState()));
            assertThat(FlushGate.awaitReached(20))
                    .as("approve reached its write without committing")
                    .isTrue();

            Outcome rivalOutcome = attempt(rival);
            FlushGate.release();
            Outcome approveOutcome = approving.get(30, TimeUnit.SECONDS);
            return new Race(approveOutcome, rivalOutcome);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertExactlyOneDecisionStands(Long id, Race race, ReservationState rivalTarget) {
        ReservationState persisted = reservations.findById(id).orElseThrow().getState();
        System.out.printf("RACE approve=%s | rival=%s | persisted=%s | notified=%s%n",
                race.approve(), race.rival(), persisted, notifier.sent);

        long successes = List.of(race.approve(), race.rival()).stream().filter(Outcome::succeeded).count();
        assertThat(successes)
                .as("approve=%s, rival=%s, persisted=%s", race.approve(), race.rival(), persisted)
                .isEqualTo(1);

        ReservationState winner = race.approve().succeeded() ? ReservationState.CONFIRMED : rivalTarget;
        assertThat(persisted).isEqualTo(winner);

        // The owner must never be told an outcome that did not commit.
        if (persisted != ReservationState.CONFIRMED) {
            assertThat(notifier.sent).doesNotContain("CONFIRMED:" + id);
        }
    }

    private static Outcome attempt(Supplier<ReservationState> operation) {
        try {
            return new Outcome(operation.get(), null);
        } catch (RuntimeException e) {
            return new Outcome(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Reservation submitted() {
        Reservation draft = service.create(memberId, gatedCourtId,
                LocalDateTime.of(2026, 9, 20, 10, 0), LocalDateTime.of(2026, 9, 20, 11, 0));
        Reservation pending = service.confirm(memberId, draft.getId());
        assertThat(pending.getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
        return pending;
    }

    private static String unique(String name) {
        return name + "-" + System.nanoTime() + "@example.org";
    }

    record Outcome(ReservationState state, String failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    record Race(Outcome approve, Outcome rival) {
    }

    /** Records what the owner was told, so a notification of an uncommitted outcome is observable. */
    static class RecordingNotifier implements NotificationService {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void notifyConfirmed(Reservation reservation) {
            sent.add("CONFIRMED:" + reservation.getId());
        }

        @Override
        public void notifyApprovalPending(Reservation reservation) {
            sent.add("PENDING:" + reservation.getId());
        }

        @Override
        public void notifyDecision(Reservation reservation) {
            sent.add(reservation.getState() + ":" + reservation.getId());
        }
    }

    @TestConfiguration
    static class RecordingNotifications {
        @Bean
        @Primary
        RecordingNotifier recordingNotifier() {
            return new RecordingNotifier();
        }
    }
}
