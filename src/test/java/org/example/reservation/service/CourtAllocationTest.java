package org.example.reservation.service;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C05 - unit tests derived from the Detailed Design of Court Allocation
 * ({@code docs/architecture-and-decisions.md} section "C05 Detailed Design").
 *
 * Unit level: no Spring context, no database. The two repository interfaces are
 * the unit's required interfaces and are mocked, so each test isolates one DD
 * decision - a row of the decision table, the predicate contract, a transition
 * or a failure behaviour. Display names cite the DD element they verify.
 */
@DisplayName("C05 DD - Court Allocation (unit)")
class CourtAllocationTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-09-20T07:30:00Z");

    private final CourtRepository courtRepository = mock(CourtRepository.class);
    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final CourtAllocation allocation = new CourtAllocation(courtRepository, reservationRepository);

    private Court court;
    private AppUser owner;
    private AppUser approver;

    @BeforeEach
    void setUp() {
        court = court(1L, true);
        owner = user(10L, AppUser.Role.MEMBER);
        approver = user(20L, AppUser.Role.STAFF);
        when(courtRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(court));
        slotIsFree();
    }

    // =================================================================
    // DR-2 / algorithm: the admissibility decision table
    // =================================================================

    @Nested
    @DisplayName("DR-2 decision table: first failing rule wins")
    class DecisionTable {

        @Test
        @DisplayName("row 1 BR-01: start == end and start > end -> INVALID_INTERVAL, no query")
        void row1_invalidInterval() {
            assertThat(allocation.assess(court, at(10, 0), at(10, 0)))
                    .isEqualTo(AvailabilityResult.Reason.INVALID_INTERVAL);
            assertThat(allocation.assess(court, at(11, 0), at(10, 0)))
                    .isEqualTo(AvailabilityResult.Reason.INVALID_INTERVAL);
            assertThat(allocation.assess(court, null, at(10, 0)))
                    .isEqualTo(AvailabilityResult.Reason.INVALID_INTERVAL);
            verifyNoInteractions(reservationRepository);
        }

        @Test
        @DisplayName("row 2 BR-07 beats rows 3 and 4: inactive, outside hours AND conflicting -> COURT_INACTIVE")
        void row2_inactiveWinsOverLaterRules() {
            Court inactive = court(1L, false);
            slotIsTaken();

            assertThat(allocation.assess(inactive, at(7, 0), at(9, 0)))
                    .isEqualTo(AvailabilityResult.Reason.COURT_INACTIVE);
            verifyNoInteractions(reservationRepository);        // short-circuit before the only DB rule
        }

        @Test
        @DisplayName("row 3 BR-04 beats row 4: outside hours AND conflicting -> OUTSIDE_OPENING_HOURS, no query")
        void row3_outsideHoursWinsOverConflict() {
            slotIsTaken();

            assertThat(allocation.assess(court, at(21, 30), at(22, 30)))
                    .isEqualTo(AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS);
            assertThat(allocation.assess(court, at(23, 0), LocalDateTime.of(2026, 9, 21, 1, 0)))
                    .as("spans a calendar day").isEqualTo(AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS);
            verifyNoInteractions(reservationRepository);
        }

        @Test
        @DisplayName("row 4 BR-02: an overlapping blocking reservation -> CONFLICT")
        void row4_conflict() {
            slotIsTaken();

            assertThat(allocation.assess(court, at(10, 0), at(11, 0)))
                    .isEqualTo(AvailabilityResult.Reason.CONFLICT);
        }

        @Test
        @DisplayName("row 5: every rule passes -> AVAILABLE; boundaries of the opening hours are inside")
        void row5_available() {
            assertThat(allocation.assess(court, at(8, 0), at(22, 0)))
                    .isEqualTo(AvailabilityResult.Reason.AVAILABLE);
        }
    }

    // =================================================================
    // DR-3 / required-interface contract: the BR-02 predicate
    // =================================================================

    @Nested
    @DisplayName("DR-3 BR-02 predicate (required-interface contract)")
    class Predicate {

        @Test
        @DisplayName("the overlap query gets exactly the blocking set {CONFIRMED} and the interval unchanged, once")
        @SuppressWarnings("unchecked")
        void predicateUsesTheSingleBlockingSet() {
            allocation.assess(court, at(10, 0), at(11, 0));

            ArgumentCaptor<Collection<ReservationState>> states = ArgumentCaptor.forClass(Collection.class);
            verify(reservationRepository).findOverlapping(eq(1L), states.capture(), eq(at(10, 0)), eq(at(11, 0)));
            assertThat(states.getValue())
                    .containsExactlyInAnyOrderElementsOf(ReservationState.blockingStates())
                    .containsExactly(ReservationState.CONFIRMED);
        }
    }

    // =================================================================
    // DR-5 / DR-6: the transitions into CONFIRMED and their failure behaviour
    // =================================================================

    @Nested
    @DisplayName("DR-5 / DR-6 transitions into CONFIRMED")
    class Transitions {

        @Test
        @DisplayName("confirm: DRAFT -> CONFIRMED under the hold, saved once")
        void confirm_draftBecomesConfirmed() {
            Reservation r = draft(at(10, 0), at(11, 0));

            allocation.confirm(allocation.hold(r), r);

            assertThat(r.getState()).isEqualTo(ReservationState.CONFIRMED);
            verify(reservationRepository).save(r);
        }

        @Test
        @DisplayName("confirmApproved: PENDING_APPROVAL -> CONFIRMED, decision recorded")
        void confirmApproved_pendingBecomesConfirmedWithDecision() {
            Reservation r = pending(at(10, 0), at(11, 0));

            allocation.confirmApproved(allocation.hold(r), r, approver, DECIDED_AT);

            assertThat(r.getState()).isEqualTo(ReservationState.CONFIRMED);
            assertThat(r.getDecidedBy()).isSameAs(approver);
            assertThat(r.getDecidedAt()).isEqualTo(DECIDED_AT);
            verify(reservationRepository).save(r);
        }

        @Test
        @DisplayName("DR-4/DR-6 confirm refused with CONFLICT: stays DRAFT, never saved")
        void confirm_conflictChangesNothing() {
            Reservation r = draft(at(10, 0), at(11, 0));
            CourtHold hold = allocation.hold(r);
            slotIsTaken();

            assertCode(() -> allocation.confirm(hold, r), ReservationErrorCode.CONFLICT);
            assertThat(r.getState()).isEqualTo(ReservationState.DRAFT);
            verify(reservationRepository, never()).save(any());
        }

        @Test
        @DisplayName("DR-4/DR-6 approve refused (court deactivated while waiting): stays PENDING, no half-written decision")
        void confirmApproved_inactiveCourtChangesNothing() {
            Reservation r = pending(at(10, 0), at(11, 0));
            CourtHold hold = allocation.hold(r);
            court.deactivate();

            assertCode(() -> allocation.confirmApproved(hold, r, approver, DECIDED_AT),
                    ReservationErrorCode.COURT_INACTIVE);
            assertThat(r.getState()).isEqualTo(ReservationState.PENDING_APPROVAL);
            assertThat(r.getDecidedBy()).isNull();
            assertThat(r.getDecidedAt()).isNull();
            verify(reservationRepository, never()).save(any());
        }

        @Test
        @DisplayName("DR-4 binding verdict maps 1:1: outside hours -> OUTSIDE_OPENING_HOURS")
        void requireAdmissible_outsideHoursCode() {
            Reservation r = draft(at(7, 0), at(8, 30));

            assertCode(() -> allocation.requireAdmissible(allocation.hold(r), r),
                    ReservationErrorCode.OUTSIDE_OPENING_HOURS);
        }

        @Test
        @DisplayName("wrong source state is a DEFECT, not a business outcome: CANCELLED -> IllegalStateException, never saved")
        void confirm_wrongSourceStateIsADefect() {
            Reservation r = draft(at(10, 0), at(11, 0));
            r.cancel(DECIDED_AT);

            assertThatThrownBy(() -> allocation.confirm(allocation.hold(r), r))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(ReservationException.class);
            assertThat(r.getState()).isEqualTo(ReservationState.CANCELLED);
            verify(reservationRepository, never()).save(any());
        }
    }

    // =================================================================
    // DR-1: the hold and its proof
    // =================================================================

    @Nested
    @DisplayName("DR-1 court hold")
    class Hold {

        @Test
        @DisplayName("hold locks the reservation's own court")
        void hold_locksTheReservationsCourt() {
            Reservation r = draft(at(10, 0), at(11, 0));

            CourtHold hold = allocation.hold(r);

            assertThat(hold.court()).isSameAs(court);
            verify(courtRepository).findByIdForUpdate(1L);
        }

        @Test
        @DisplayName("hold on a missing court -> COURT_NOT_FOUND")
        void hold_missingCourt() {
            when(courtRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
            Reservation r = draft(at(10, 0), at(11, 0));

            assertCode(() -> allocation.hold(r), ReservationErrorCode.COURT_NOT_FOUND);
        }

        @Test
        @DisplayName("a hold on ANOTHER court is a defect: IllegalArgumentException before any query or change")
        void holdOnAnotherCourtIsRefused() {
            Court otherCourt = court(2L, true);
            when(courtRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(otherCourt));
            Reservation onOtherCourt = new Reservation(otherCourt, owner, at(10, 0), at(11, 0), DECIDED_AT);
            CourtHold holdOnOtherCourt = allocation.hold(onOtherCourt);
            Reservation r = draft(at(10, 0), at(11, 0));                // court 1

            assertThatThrownBy(() -> allocation.confirm(holdOnOtherCourt, r))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(r.getState()).isEqualTo(ReservationState.DRAFT);
            verifyNoInteractions(reservationRepository);
        }
    }

    // =================================================================
    // helpers
    // =================================================================

    private void slotIsFree() {
        when(reservationRepository.findOverlapping(any(), any(), any(), any())).thenReturn(List.of());
    }

    private void slotIsTaken() {
        Reservation rival = new Reservation(court, user(11L, AppUser.Role.MEMBER), at(10, 0), at(11, 0), DECIDED_AT);
        rival.confirm();
        when(reservationRepository.findOverlapping(any(), any(), any(), any())).thenReturn(List.of(rival));
    }

    private Reservation draft(LocalDateTime start, LocalDateTime end) {
        return new Reservation(court, owner, start, end, DECIDED_AT);
    }

    private Reservation pending(LocalDateTime start, LocalDateTime end) {
        Reservation r = draft(start, end);
        r.submitForApproval(start);
        return r;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   ReservationErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ReservationException.class)
                .extracting(e -> ((ReservationException) e).getCode())
                .isEqualTo(expected);
    }

    private static Court court(long id, boolean active) {
        Court c = new Court("Court " + id, Court.CourtType.SQUASH, "Hall B",
                LocalTime.of(8, 0), LocalTime.of(22, 0), active);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static AppUser user(long id, AppUser.Role role) {
        AppUser u = new AppUser("User " + id, "user" + id + "@example.com", role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute);
    }
}
