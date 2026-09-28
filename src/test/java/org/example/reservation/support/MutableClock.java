package org.example.reservation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * BR-06 / AD-2 made testable: a clock the verification examples can place
 * exactly on a boundary (e.g. V-04.4, "now == start").
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(LocalDateTime now, ZoneId zone) {
        this.zone = zone;
        this.instant = now.atZone(zone).toInstant();
    }

    /** Move facility-local "now" to an exact wall-clock moment. */
    public void setTo(LocalDateTime facilityLocalNow) {
        this.instant = facilityLocalNow.atZone(zone).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(LocalDateTime.ofInstant(instant, newZone), newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
