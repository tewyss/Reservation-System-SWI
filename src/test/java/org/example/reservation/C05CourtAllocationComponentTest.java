package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.repository.ReservationRepository;
import org.example.reservation.service.AvailabilityResult;
import org.example.reservation.service.CourtAllocation;
import org.example.reservation.service.ReservationException;
import org.example.reservation.service.ReservationService;
import org.example.reservation.support.MutableClock;
import org.example.reservation.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C05 - component tests derived from the Detailed Design of Court Allocation
 * ({@code docs/architecture-and-decisions.md} section "C05 Detailed Design").
 *
 * Component level: the real Spring context, transaction proxies and H2. These
 * DD decisions cannot be seen with mocks - one is a transaction-boundary
 * contract, the other a collaboration between two callers of the unit.
 */
@SpringBootTest
@Import(TestClockConfig.class)
@DisplayName("C05 DD - Court Allocation (component)")
class C05CourtAllocationComponentTest {

    @Autowired private ReservationService service;
    @Autowired private CourtAllocation courtAllocation;
    @Autowired private CourtRepository courts;
    @Autowired private AppUserRepository users;
    @Autowired private ReservationRepository reservations;
    @Autowired private MutableClock clock;

    private Court court;
    private Long memberId;

    @BeforeEach
    void setUp() {
        clock.setTo(TestClockConfig.FIXTURE_NOW);
        reservations.deleteAll();
        court = courts.save(new Court("Court C05", Court.CourtType.SQUASH, "Hall D",
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        memberId = users.save(new AppUser("Mia", "mia+" + System.nanoTime() + "@example.com",
                AppUser.Role.MEMBER)).getId();
    }

    @Test
    @DisplayName("DR-1 contract: the hold exists only inside the caller's transaction - no transaction, no hold")
    void holdWithoutTransactionIsRefused() {
        Reservation r = service.create(memberId, court.getId(), at(10, 0), at(11, 0));

        assertThatThrownBy(() -> courtAllocation.hold(r))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(reservations.findById(r.getId()).orElseThrow().getState())
                .isEqualTo(ReservationState.DRAFT);
    }

    @Test
    @DisplayName("DR-2 collaboration: OP-02 (advisory) and OP-03 (binding) give the same verdict for every row")
    void availabilityAndConfirmAgreeOnEveryRow() {
        Reservation taken = service.create(memberId, court.getId(), at(14, 0), at(15, 0));
        service.confirm(memberId, taken.getId());

        assertSameVerdict(at(10, 0), at(11, 0), AvailabilityResult.Reason.AVAILABLE);          // row 5
        assertSameVerdict(at(14, 30), at(15, 30), AvailabilityResult.Reason.CONFLICT);         // row 4
        assertSameVerdict(at(7, 0), at(8, 30), AvailabilityResult.Reason.OUTSIDE_OPENING_HOURS); // row 3

        // row 2: inactive AND outside hours AND conflicting. OP-01 refuses an inactive
        // court (BR-07), so the draft is created first and the court closed afterwards.
        Reservation waiting = service.create(memberId, court.getId(), at(14, 30), at(22, 30));
        court.deactivate();
        courts.save(court);
        assertSameVerdict(waiting, AvailabilityResult.Reason.COURT_INACTIVE);
    }

    private void assertSameVerdict(LocalDateTime start, LocalDateTime end, AvailabilityResult.Reason expected) {
        assertSameVerdict(service.create(memberId, court.getId(), start, end), expected);
    }

    /**
     * Asks availability for the draft's slot first (no hold), then confirms the
     * draft (under the hold): an AVAILABLE verdict must allocate, any other
     * verdict must fail with the error code of the same name and change nothing.
     */
    private void assertSameVerdict(Reservation draft, AvailabilityResult.Reason expected) {
        LocalDateTime start = draft.getStartTime();
        LocalDateTime end = draft.getEndTime();
        AvailabilityResult advisory = service.checkAvailability(court.getId(), start, end);
        assertThat(advisory.reason()).as("OP-02 for [%s,%s)", start, end).isEqualTo(expected);

        if (expected == AvailabilityResult.Reason.AVAILABLE) {
            assertThat(service.confirm(memberId, draft.getId()).getState()).isEqualTo(ReservationState.CONFIRMED);
            return;
        }
        assertThatThrownBy(() -> service.confirm(memberId, draft.getId()))
                .as("OP-03 for [%s,%s)", start, end)
                .isInstanceOf(ReservationException.class)
                .extracting(e -> ((ReservationException) e).getCode().name())
                .isEqualTo(expected.name());
        assertThat(reservations.findById(draft.getId()).orElseThrow().getState())
                .isEqualTo(ReservationState.DRAFT);
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute);
    }
}
