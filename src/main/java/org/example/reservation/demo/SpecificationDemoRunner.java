package org.example.reservation.demo;

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
import org.example.reservation.service.ApprovalWorkflow;
import org.example.reservation.service.ReservationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.function.Supplier;

/**
 * Drives the RUNNING application through every operation of Specification
 * Baseline v0.2 and prints a transcript, so the behaviour can be inspected
 * without reading test code.
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.profiles=demo</pre>
 *
 * It exercises one success example and one negative/boundary example per slice.
 * The authoritative, assert-backed verification lives in the JUnit suites; this
 * runner is the human-readable demonstration of the same behaviour.
 *
 * It exits non-zero if any expectation is not met.
 */
@Component
@Profile("demo")
public class SpecificationDemoRunner implements ApplicationRunner {

    private final ReservationService service;
    private final ApprovalWorkflow approvals;
    private final CourtRepository courts;
    private final AppUserRepository users;
    private final ReservationRepository reservations;
    private final ConfigurableApplicationContext context;

    private int checks;
    private int failures;

    public SpecificationDemoRunner(ReservationService service, ApprovalWorkflow approvals,
                                   CourtRepository courts, AppUserRepository users,
                                   ReservationRepository reservations,
                                   ConfigurableApplicationContext context) {
        this.service = service;
        this.approvals = approvals;
        this.courts = courts;
        this.users = users;
        this.reservations = reservations;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Fixtures. Tomorrow, so that "now < start" holds for the cancel boundary.
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate yesterday = LocalDate.now().minusDays(1);

        Long openCourt = courts.save(new Court("Court 2 (self-service)", Court.CourtType.SQUASH,
                "Hall B", LocalTime.of(8, 0), LocalTime.of(22, 0), true, false)).getId();
        Long gatedCourt = courts.save(new Court("Centre Court (approval)", Court.CourtType.TENNIS,
                "Hall A", LocalTime.of(8, 0), LocalTime.of(22, 0), true, true)).getId();
        Long alice = users.save(new AppUser("Alice", "alice@example.com", AppUser.Role.MEMBER)).getId();
        Long bob = users.save(new AppUser("Bob", "bob@example.com", AppUser.Role.MEMBER)).getId();
        Long dana = users.save(new AppUser("Dana (manager)", "dana@example.com", AppUser.Role.STAFF)).getId();

        banner("SWI Reservation System - Specification Baseline v0.2 demonstration");
        System.out.printf("  self-service court id=%d, approval-gated court id=%d%n", openCourt, gatedCourt);
        System.out.printf("  member Alice id=%d, member Bob id=%d, approver Dana id=%d (STAFF)%n", alice, bob, dana);
        System.out.printf("  fixture day = %s (tomorrow), past day = %s%n", tomorrow, yesterday);

        // ---------------------------------------------------------------
        section("OP-01 Create Reservation  (REQ-01)");
        Reservation draft = service.create(alice, openCourt, at(tomorrow, 10, 0), at(tomorrow, 11, 0));
        expect("V-01.1 success: valid interval -> DRAFT with an id",
                draft.getState() == ReservationState.DRAFT && draft.getId() != null,
                "state=" + draft.getState() + ", id=" + draft.getId());
        expectRejected("V-01.3 boundary: start == end -> INVALID_INTERVAL",
                ReservationErrorCode.INVALID_INTERVAL,
                () -> service.create(alice, openCourt, at(tomorrow, 12, 0), at(tomorrow, 12, 0)));

        // ---------------------------------------------------------------
        section("OP-02 Check Availability  (REQ-02, REQ-13)");
        AvailabilityResult free = service.checkAvailability(openCourt, at(tomorrow, 10, 0), at(tomorrow, 11, 0));
        expect("V-01.1 postcondition: a DRAFT allocates nothing -> still AVAILABLE",
                free.available(), describe(free));
        AvailabilityResult closed = service.checkAvailability(openCourt, at(tomorrow, 21, 30), at(tomorrow, 22, 30));
        expect("V-02.7 boundary: 21:30-22:30 past a 22:00 closing -> OUTSIDE_OPENING_HOURS",
                !closed.available()
                        && closed.reason() == AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS,
                describe(closed));

        // ---------------------------------------------------------------
        section("OP-03 Confirm Reservation  (REQ-03, REQ-08, REQ-09)");
        Reservation confirmed = service.confirm(alice, draft.getId());
        expect("V-03.1 success: self-service court -> CONFIRMED",
                confirmed.getState() == ReservationState.CONFIRMED, "state=" + confirmed.getState());
        AvailabilityResult taken = service.checkAvailability(openCourt, at(tomorrow, 10, 0), at(tomorrow, 11, 0));
        expect("V-03.1 postcondition: the slot now blocks -> UNAVAILABLE / CONFLICT",
                !taken.available() && taken.reason() == AvailabilityResult.Reason.CONFLICT, describe(taken));
        AvailabilityResult touching = service.checkAvailability(openCourt, at(tomorrow, 11, 0), at(tomorrow, 12, 0));
        expect("V-02.3 boundary: [11:00,12:00) touching a CONFIRMED [10:00,11:00) -> AVAILABLE",
                touching.available(), describe(touching));

        Reservation clashing = service.create(bob, openCourt, at(tomorrow, 10, 30), at(tomorrow, 11, 30));
        expectRejected("V-03.2 negative: overlapping confirm -> CONFLICT",
                ReservationErrorCode.CONFLICT, () -> service.confirm(bob, clashing.getId()));
        expect("V-03.2 postcondition: the loser REMAINS DRAFT",
                service.checkAvailability(openCourt, at(tomorrow, 10, 30), at(tomorrow, 11, 30)) != null
                        && reload(clashing.getId()) == ReservationState.DRAFT,
                "state=" + reload(clashing.getId()));

        // ---------------------------------------------------------------
        section("OP-04 Cancel Reservation  (REQ-05, REQ-06)");
        Reservation cancelled = service.cancel(alice, confirmed.getId());
        expect("V-04.2 success: cancelling a CONFIRMED reservation -> CANCELLED",
                cancelled.getState() == ReservationState.CANCELLED, "state=" + cancelled.getState());
        AvailabilityResult freedAgain = service.checkAvailability(openCourt, at(tomorrow, 10, 0), at(tomorrow, 11, 0));
        expect("V-04.2 postcondition: the slot is AVAILABLE again",
                freedAgain.available(), describe(freedAgain));
        Reservation again = service.cancel(alice, confirmed.getId());
        expect("V-04.3 REQ-06 idempotency: a repeated cancel SUCCEEDS and stays CANCELLED",
                again.getState() == ReservationState.CANCELLED, "state=" + again.getState());
        Reservation past = service.create(alice, openCourt, at(yesterday, 10, 0), at(yesterday, 11, 0));
        expectRejected("V-04.5 boundary: cancelling a slot that already started -> TOO_LATE_TO_CANCEL",
                ReservationErrorCode.TOO_LATE_TO_CANCEL, () -> service.cancel(alice, past.getId()));
        // "past" is owned by Alice; authorization is checked before the time boundary.
        expectRejected("V-04.6 negative: Bob cancels Alice's reservation -> UNAUTHORIZED",
                ReservationErrorCode.UNAUTHORIZED, () -> service.cancel(bob, past.getId()));

        // ---------------------------------------------------------------
        section("OP-03 + OP-05 the approval gate  (REQ-09, REQ-10, REQ-13, REQ-14)");
        Reservation gatedDraft = service.create(alice, gatedCourt, at(tomorrow, 14, 0), at(tomorrow, 15, 0));
        Reservation pending = service.confirm(alice, gatedDraft.getId());
        expect("V-03.10 success: confirm on a GATED court -> PENDING_APPROVAL, deadline set",
                pending.getState() == ReservationState.PENDING_APPROVAL
                        && pending.getApprovalDeadline() != null,
                "state=" + pending.getState() + ", deadline=" + pending.getApprovalDeadline());

        Reservation rival = service.create(bob, gatedCourt, at(tomorrow, 14, 0), at(tomorrow, 15, 0));
        service.confirm(bob, rival.getId());
        AvailabilityResult disclosed = service.checkAvailability(gatedCourt, at(tomorrow, 14, 0), at(tomorrow, 15, 0));
        expect("V-02.11 REQ-13: 2 pending requests do NOT block, but ARE disclosed",
                disclosed.available() && disclosed.approvalRequired()
                        && disclosed.pendingApprovalCount() == 2,
                describe(disclosed));

        expectRejected("V-05.2 negative: a MEMBER cannot approve -> UNAUTHORIZED",
                ReservationErrorCode.UNAUTHORIZED, () -> approvals.approve(bob, pending.getId()));
        Reservation approved = approvals.approve(dana, pending.getId());
        expect("V-05.1 success: approver Dana -> CONFIRMED, decision recorded",
                approved.getState() == ReservationState.CONFIRMED
                        && approved.getDecidedBy() != null,
                "state=" + approved.getState() + ", decidedBy=" + approved.getDecidedBy().getId());
        expectRejected("V-05.5 negative: the rival request lost the slot -> CONFLICT (stays PENDING_APPROVAL)",
                ReservationErrorCode.CONFLICT, () -> approvals.approve(dana, rival.getId()));
        expect("V-05.5 postcondition: a failed approval is NOT a decision",
                reload(rival.getId()) == ReservationState.PENDING_APPROVAL,
                "state=" + reload(rival.getId()));

        // ---------------------------------------------------------------
        section("OP-06 Reject Reservation  (REQ-11)");
        Reservation rejected = approvals.reject(dana, rival.getId(), "Court reserved for a tournament");
        expect("V-06.1 success: reject -> REJECTED, terminal, nothing allocated",
                rejected.getState() == ReservationState.REJECTED, "state=" + rejected.getState()
                        + ", reason=" + rejected.getDecisionReason());
        expectRejected("V-06.2 BR-11 terminality: a REJECTED request cannot be approved",
                ReservationErrorCode.INVALID_STATE, () -> approvals.approve(dana, rival.getId()));

        // ---------------------------------------------------------------
        section("OP-07 Expire Pending Approvals  (REQ-12)");
        // A request for a slot that already started: BR-10 clamps the deadline to
        // the start, so it is overdue the moment it is submitted.
        Reservation overdueDraft = service.create(alice, gatedCourt, at(yesterday, 14, 0), at(yesterday, 15, 0));
        Reservation overdue = service.confirm(alice, overdueDraft.getId());
        expect("BR-10: the approval deadline is clamped to the reservation start",
                overdue.getApprovalDeadline().equals(at(yesterday, 14, 0)),
                "deadline=" + overdue.getApprovalDeadline() + ", start=" + at(yesterday, 14, 0));
        expectRejected("V-05.4 boundary: approving past the deadline -> APPROVAL_EXPIRED",
                ReservationErrorCode.APPROVAL_EXPIRED, () -> approvals.approve(dana, overdue.getId()));
        expect("V-05.4 / REQ-12 lazy expiry: the failed attempt left it EXPIRED",
                reload(overdue.getId()) == ReservationState.EXPIRED, "state=" + reload(overdue.getId()));

        Reservation overdue2 = service.confirm(alice,
                service.create(alice, gatedCourt, at(yesterday, 16, 0), at(yesterday, 17, 0)).getId());
        int swept = approvals.expirePendingApprovals();
        expect("V-07.1 success: the sweep expires the overdue request",
                swept >= 1 && reload(overdue2.getId()) == ReservationState.EXPIRED,
                "swept=" + swept + ", state=" + reload(overdue2.getId()));
        expect("V-07.4 G1: the sweep is idempotent - nothing left to do",
                approvals.expirePendingApprovals() == 0, "second sweep changed 0");
        expectRejected("V-04.10 D7: an EXPIRED request cannot be cancelled",
                ReservationErrorCode.INVALID_STATE, () -> service.cancel(alice, overdue2.getId()));

        // ---------------------------------------------------------------
        banner(String.format("RESULT: %d checks, %d failed", checks, failures));
        System.exit(SpringApplication.exit(context, () -> failures == 0 ? 0 : 1));
    }

    // ------------------------------------------------------------------

    /** Read the committed state back, so postconditions are observed and not assumed. */
    private ReservationState reload(Long id) {
        return reservations.findById(id).orElseThrow().getState();
    }

    private void expect(String label, boolean condition, String observed) {
        checks++;
        if (condition) {
            System.out.printf("  [PASS] %s%n         observed: %s%n", label, observed);
        } else {
            failures++;
            System.out.printf("  [FAIL] %s%n         observed: %s%n", label, observed);
        }
    }

    private void expectRejected(String label, ReservationErrorCode expected, Supplier<?> call) {
        checks++;
        try {
            call.get();
            failures++;
            System.out.printf("  [FAIL] %s%n         observed: no rejection at all%n", label);
        } catch (ReservationException e) {
            if (e.getCode() == expected) {
                System.out.printf("  [PASS] %s%n         observed: %s - %s%n", label, e.getCode(), e.getMessage());
            } else {
                failures++;
                System.out.printf("  [FAIL] %s%n         observed: %s (expected %s)%n",
                        label, e.getCode(), expected);
            }
        }
    }

    private static String describe(AvailabilityResult r) {
        return String.format("available=%s, reason=%s, approvalRequired=%s, pendingApprovalCount=%d",
                r.available(), r.reason(), r.approvalRequired(), r.pendingApprovalCount());
    }

    private static LocalDateTime at(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, LocalTime.of(hour, minute));
    }

    private static void banner(String text) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(text);
        System.out.println("=".repeat(78));
    }

    private static void section(String text) {
        System.out.println();
        System.out.println("--- " + text + " " + "-".repeat(Math.max(0, 72 - text.length())));
    }
}
