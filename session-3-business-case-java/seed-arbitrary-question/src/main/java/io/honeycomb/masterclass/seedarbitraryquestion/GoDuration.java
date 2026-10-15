package io.honeycomb.masterclass.seedarbitraryquestion;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and prints durations the way Go does ({@code time.ParseDuration} and
 * {@code Duration.String}).
 *
 * <p>This exists for two reasons that both come from keeping the Go seeder's
 * behaviour. {@code SEED_WINDOW} has always been written in Go's syntax
 * ({@code 168h}, {@code 90m}, {@code 1h30m}), and the closing summary printed
 * "over the last 168h0m0s". The JDK reads only ISO-8601 ({@code PT168H}), so
 * without this class existing scripts and the printed text would change.
 */
final class GoDuration {

    private static final Pattern PART = Pattern.compile("(\\d*\\.?\\d*)(ns|us|\u00b5s|\u03bcs|ms|s|m|h)");

    private GoDuration() {}

    /** Parses text like {@code "4h"}, {@code "1h30m"}, {@code "1.5s"} or {@code "-90m"}. */
    static Duration parse(String text) {
        String s = text;
        boolean negative = false;
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            negative = s.charAt(0) == '-';
            s = s.substring(1);
        }
        if (s.equals("0")) {
            return Duration.ZERO;
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("invalid duration: \"" + text + "\"");
        }

        BigDecimal totalNanos = BigDecimal.ZERO;
        int pos = 0;
        Matcher m = PART.matcher(s);
        while (pos < s.length()) {
            if (!m.find(pos) || m.start() != pos || m.group(1).isEmpty() || m.group(1).equals(".")) {
                throw new IllegalArgumentException("invalid duration: \"" + text + "\"");
            }
            totalNanos = totalNanos.add(new BigDecimal(m.group(1)).multiply(nanosPerUnit(m.group(2))));
            pos = m.end();
        }

        long nanos = totalNanos.longValueExact();
        return Duration.ofNanos(negative ? -nanos : nanos);
    }

    private static BigDecimal nanosPerUnit(String unit) {
        return switch (unit) {
            case "ns" -> BigDecimal.valueOf(1L);
            case "us", "\u00b5s", "\u03bcs" -> BigDecimal.valueOf(1_000L);
            case "ms" -> BigDecimal.valueOf(1_000_000L);
            case "s" -> BigDecimal.valueOf(1_000_000_000L);
            case "m" -> BigDecimal.valueOf(60_000_000_000L);
            case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
            default -> throw new IllegalArgumentException("unknown unit: " + unit);
        };
    }

    /** Prints like Go: {@code 168h0m0s}, {@code 1m30s}, {@code 1.5s}, {@code 250ms}, {@code 0s}. */
    static String format(Duration d) {
        long nanos = d.toNanos();
        if (nanos == 0) {
            return "0s";
        }
        String sign = nanos < 0 ? "-" : "";
        long u = Math.abs(nanos);

        if (u < 1_000_000_000L) {
            if (u < 1_000L) {
                return sign + u + "ns";
            }
            if (u < 1_000_000L) {
                return sign + fraction(u, 3) + "\u00b5s";
            }
            return sign + fraction(u, 6) + "ms";
        }

        StringBuilder b = new StringBuilder(fraction(u % 60_000_000_000L, 9)).append('s');
        long minutes = u / 60_000_000_000L;
        if (minutes > 0) {
            b.insert(0, "m").insert(0, minutes % 60);
            long hours = minutes / 60;
            if (hours > 0) {
                b.insert(0, "h").insert(0, hours);
            }
        }
        return sign + b;
    }

    /** {@code value / 10^digits}, with trailing fractional zeros dropped. */
    private static String fraction(long value, int digits) {
        long scale = (long) Math.pow(10, digits);
        long whole = value / scale;
        long rest = value % scale;
        if (rest == 0) {
            return Long.toString(whole);
        }
        String frac = String.format("%0" + digits + "d", rest).replaceAll("0+$", "");
        return whole + "." + frac;
    }
}
