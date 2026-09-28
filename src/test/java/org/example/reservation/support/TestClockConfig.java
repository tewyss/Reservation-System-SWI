package org.example.reservation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Replaces the production {@code facilityClock} with a clock the tests control,
 * so time-boundary behaviour is verified rather than hoped for.
 *
 * {@link MutableClock} is a {@code java.time.Clock}, so marking it primary is
 * enough for {@code ReservationService} to receive it.
 */
@TestConfiguration
public class TestClockConfig {

    /** All verification examples use the fixture day 2026-09-20, "now" = 09:00. */
    public static final LocalDateTime FIXTURE_NOW = LocalDateTime.of(2026, 9, 20, 9, 0);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(FIXTURE_NOW, ZoneId.systemDefault());
    }
}
