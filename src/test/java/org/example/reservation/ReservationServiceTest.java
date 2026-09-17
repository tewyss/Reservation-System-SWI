package org.example.reservation;

import org.example.reservation.domain.AppUser;
import org.example.reservation.domain.Court;
import org.example.reservation.domain.Reservation;
import org.example.reservation.domain.ReservationState;
import org.example.reservation.repository.AppUserRepository;
import org.example.reservation.repository.CourtRepository;
import org.example.reservation.service.ReservationException;
import org.example.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the two business rules and the core operations through the service.
 */
@SpringBootTest
class ReservationServiceTest {

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private CourtRepository courtRepository;
    @Autowired
    private AppUserRepository userRepository;

    private Long courtId;
    private Long userId;

    @BeforeEach
    void setUp() {
        Court court = courtRepository.save(new Court(
                "Court 1", Court.CourtType.SQUASH, "Hall B",
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        AppUser user = userRepository.save(new AppUser("Alice", "alice+" + System.nanoTime() + "@ex.com"));
        courtId = court.getId();
        userId = user.getId();
    }

    @Test
    @DisplayName("create + confirm moves a reservation DRAFT -> CONFIRMED")
    void createThenConfirm() {
        Reservation r = reservationService.create(courtId, userId,
                at(10, 0), at(11, 0));
        assertThat(r.getState()).isEqualTo(ReservationState.DRAFT);

        Reservation confirmed = reservationService.confirm(r.getId());
        assertThat(confirmed.getState()).isEqualTo(ReservationState.CONFIRMED);
    }

    @Test
    @DisplayName("Common rule: two CONFIRMED reservations of the same court must not overlap")
    void overlappingConfirmedIsRejected() {
        Reservation first = reservationService.create(courtId, userId, at(10, 0), at(11, 0));
        reservationService.confirm(first.getId());

        Reservation overlapping = reservationService.create(courtId, userId, at(10, 30), at(11, 30));
        assertThatThrownBy(() -> reservationService.confirm(overlapping.getId()))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("overlaps");
    }

    @Test
    @DisplayName("A cancelled reservation frees the slot for a new confirmation")
    void cancelFreesSlot() {
        Reservation first = reservationService.create(courtId, userId, at(10, 0), at(11, 0));
        reservationService.confirm(first.getId());
        reservationService.cancel(first.getId());

        Reservation second = reservationService.create(courtId, userId, at(10, 0), at(11, 0));
        Reservation confirmed = reservationService.confirm(second.getId());
        assertThat(confirmed.getState()).isEqualTo(ReservationState.CONFIRMED);
    }

    @Test
    @DisplayName("Domain rule: a reservation outside the court's opening hours is rejected")
    void outsideOpeningHoursIsRejected() {
        // Court closes at 22:00; 21:30-22:30 spills past closing.
        Reservation late = reservationService.create(courtId, userId, at(21, 30), at(22, 30));
        assertThatThrownBy(() -> reservationService.confirm(late.getId()))
                .isInstanceOf(ReservationException.class)
                .hasMessageContaining("opening hours");
    }

    @Test
    @DisplayName("checkAvailability reflects confirmed reservations")
    void availabilityReflectsConfirmations() {
        assertThat(reservationService.isAvailable(courtId, at(14, 0), at(15, 0))).isTrue();

        Reservation r = reservationService.create(courtId, userId, at(14, 0), at(15, 0));
        reservationService.confirm(r.getId());

        assertThat(reservationService.isAvailable(courtId, at(14, 0), at(15, 0))).isFalse();
        assertThat(reservationService.isAvailable(courtId, at(15, 0), at(16, 0))).isTrue();
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute);
    }
}
