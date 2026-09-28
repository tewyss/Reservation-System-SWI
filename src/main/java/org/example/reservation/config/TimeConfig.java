package org.example.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * BR-06 / architecture driver AD-2: time is injected, never ambient.
 *
 * Every time-dependent rule (notably the cancellation boundary BR-03.2) reads
 * {@code now} from this single {@link Clock}, so the boundary is observable and
 * a test can place "now" exactly on it.
 */
@Configuration
public class TimeConfig {

    /** Facility-local clock. The facility operates in a single time zone (C01 assumption). */
    @Bean
    public Clock facilityClock() {
        return Clock.systemDefaultZone();
    }
}
