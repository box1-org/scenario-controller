package org.box1.scenario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Display-only stardate (D-008): 1000 x (year - 2323) + 1000 x the fraction of the UTC year elapsed, one decimal.
 * The ISO 8601 timestamp beside it is authoritative.
 */
public final class Stardate {
    private Stardate() {}

    public static String of(Instant instant) {
        LocalDateTime time = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        LocalDateTime start = LocalDateTime.of(time.getYear(), 1, 1, 0, 0);
        double length = ChronoUnit.SECONDS.between(start, start.plusYears(1));
        double elapsed = ChronoUnit.MILLIS.between(start, time) / 1000.0;
        double value = 1000.0 * (time.getYear() - 2323) + 1000.0 * elapsed / length;
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
