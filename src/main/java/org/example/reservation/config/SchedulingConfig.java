package org.example.reservation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the time-based trigger that OP-07 needs.
 *
 * Architecture driver AD-5: this is the first non-request entry point in the
 * system. C03 has to decide who owns it, how often it runs, and what happens when
 * two instances run it at once. C02 only makes it work.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
