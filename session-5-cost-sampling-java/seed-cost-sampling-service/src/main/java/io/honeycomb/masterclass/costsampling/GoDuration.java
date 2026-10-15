package io.honeycomb.masterclass.costsampling;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and prints durations in Go's text form, such as {@code 90s},
 * {@code 1m30s}, {@code 10ms} or {@code 1.5h}.
 *
 * <p>The Go seeder takes {@code SEED_DURATION} and {@code SEED_SETTLE_WAIT}
 * through {@code time.ParseDuration}, and prints durations with
 * {@code %s} (which calls {@code Duration.String()}). Java has no built-in
 * for this form ({@link Duration#parse} only reads ISO-8601 such as
 * {@code PT90S}), and the env vars are part of the seeder's contract, so
 * this class keeps the same spelling working. It is a small, self-contained
 * port of those two Go functions.
 */
public final class GoDuration {

    private GoDuration() {}

    private static final Pattern TERM = Pattern.compile("(\\d+\\.?\\d*|\\.\\d+)(ns|us|µs|μs|ms|s|m|h)");

    /** Mirrors {@code time.ParseDuration}; an empty result means Go would return an error. */
    public static Optional<Duration> parse(String text) {
        String s = text;
        boolean negative = false;
        if (s.startsWith("-") || s.startsWith("+")) {
            negative = s.startsWith("-");
            s = s.substring(1);
        }
        if (s.equals("0")) {
            return Optional.of(Duration.ZERO);
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal totalNanos = BigDecimal.ZERO;
        Matcher m = TERM.matcher(s);
        int pos = 0;
        while (pos < s.length()) {
            // Each term must start exactly where the last one ended.
            if (!m.find(pos) || m.start() != pos) {
                return Optional.empty();
            }
            totalNanos = totalNanos.add(new BigDecimal(m.group(1)).multiply(nanosPer(m.group(2))));
            pos = m.end();
        }

        BigInteger nanos = totalNanos.toBigInteger();
        if (nanos.bitLength() > 62) {
            return Optional.empty();
        }
        long value = nanos.longValue();
        return Optional.of(Duration.ofNanos(negative ? -value : value));
    }

    private static BigDecimal nanosPer(String unit) {
        return BigDecimal.valueOf(switch (unit) {
            case "ns" -> 1L;
            case "us", "µs", "μs" -> 1_000L;
            case "ms" -> 1_000_000L;
            case "s" -> 1_000_000_000L;
            case "m" -> 60_000_000_000L;
            case "h" -> 3_600_000_000_000L;
            default -> throw new IllegalArgumentException("unknown unit " + unit);
        });
    }

    /** Mirrors {@code time.Duration.String()}: {@code 0s}, {@code 10ms}, {@code 1m30s}, {@code 1h0m0s}. */
    public static String format(Duration d) {
        long nanos = d.toNanos();
        if (nanos == 0) {
            return "0s";
        }
        if (nanos < 0) {
            return "-" + format(Duration.ofNanos(-nanos));
        }
        if (nanos < 1_000) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000) {
            return fraction(nanos, 1_000) + "µs";
        }
        if (nanos < 1_000_000_000) {
            return fraction(nanos, 1_000_000) + "ms";
        }

        long hours = nanos / 3_600_000_000_000L;
        long minutes = nanos / 60_000_000_000L % 60;
        String seconds = fraction(nanos % 60_000_000_000L, 1_000_000_000L) + "s";
        if (hours > 0) {
            return hours + "h" + minutes + "m" + seconds;
        }
        if (minutes > 0) {
            return minutes + "m" + seconds;
        }
        return seconds;
    }

    /** Prints value/unit with up to nine decimals and no trailing zeros, like Go. */
    private static String fraction(long value, long unit) {
        // Dividing by a power of ten always ends, so exact divide() is safe.
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(unit)).toPlainString();
    }
}
