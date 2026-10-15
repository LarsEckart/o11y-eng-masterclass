package io.honeycomb.masterclass.everydomain.seedperfservice;

import java.time.Duration;
import java.util.Locale;

/**
 * Prints durations the way Go's {@code time.Duration.String()} does ("1.5s",
 * "300ms", "12.345678ms").
 *
 * <p>The seeder's summary prints P50 and P95 with {@code %s}, and the Go README
 * and the Honeycomb queries talk about those numbers in that form. Java's own
 * {@link Duration#toString()} gives ISO-8601 ("PT0.012345678S"), which nobody
 * wants to compare by eye. Session 2 has a fuller version of this class that
 * also parses; this seeder only prints.</p>
 */
final class GoDuration {

    private GoDuration() {
    }

    static String format(Duration d) {
        long nanos = d.toNanos();
        if (nanos == 0) {
            return "0s";
        }
        String sign = nanos < 0 ? "-" : "";
        long abs = Math.abs(nanos);

        if (abs < 1_000_000_000L) {
            if (abs < 1_000L) {
                return sign + abs + "ns";
            }
            if (abs < 1_000_000L) {
                return sign + trim(abs / 1_000.0) + "µs";
            }
            return sign + trim(abs / 1_000_000.0) + "ms";
        }

        long hours = abs / 3_600_000_000_000L;
        long minutes = abs / 60_000_000_000L % 60;
        double seconds = (abs % 60_000_000_000L) / 1_000_000_000.0;

        var out = new StringBuilder(sign);
        if (hours > 0) {
            out.append(hours).append('h');
        }
        if (hours > 0 || minutes > 0) {
            out.append(minutes).append('m');
        }
        out.append(trim(seconds)).append('s');
        return out.toString();
    }

    /** Up to 9 decimal places, trailing zeros removed. */
    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.9f", value);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
