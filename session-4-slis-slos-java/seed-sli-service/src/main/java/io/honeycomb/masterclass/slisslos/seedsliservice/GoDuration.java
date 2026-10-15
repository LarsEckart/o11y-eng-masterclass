package io.honeycomb.masterclass.slisslos.seedsliservice;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and prints durations the way Go does ({@code time.ParseDuration} and
 * {@code time.Duration.String}).
 *
 * <p>The Go seeder reads {@code SEED_WINDOW=6h} or {@code SEED_INCIDENT_AGO=90m}
 * with {@code time.ParseDuration}, and its summary prints the window as
 * {@code 6h0m0s}. Java's {@link Duration} only speaks ISO-8601 ({@code PT6H}),
 * so this small class keeps the env vars and the output identical to the Go
 * version. It lives here because only the seeder needs it.
 */
final class GoDuration {

    private static final Pattern WHOLE = Pattern.compile("^[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:ns|us|µs|μs|ms|s|m|h)(?:(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:ns|us|µs|μs|ms|s|m|h))*$");
    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)(ns|us|µs|μs|ms|s|m|h)");

    private GoDuration() {
    }

    /** Accepts forms like {@code 6h}, {@code 1h30m}, {@code 1.5h}, {@code 300ms}, and {@code 0}. */
    static Optional<Duration> parse(String text) {
        if (text.equals("0") || text.equals("+0") || text.equals("-0")) {
            return Optional.of(Duration.ZERO);
        }
        if (!WHOLE.matcher(text).matches()) {
            return Optional.empty();
        }
        double nanos = 0;
        Matcher m = PART.matcher(text);
        while (m.find()) {
            double amount = Double.parseDouble(m.group(1));
            nanos += amount * unitNanos(m.group(2));
        }
        if (text.startsWith("-")) {
            nanos = -nanos;
        }
        return Optional.of(Duration.ofNanos(Math.round(nanos)));
    }

    private static double unitNanos(String unit) {
        return switch (unit) {
            case "ns" -> 1;
            case "us", "µs", "μs" -> 1_000;
            case "ms" -> 1_000_000;
            case "s" -> 1_000_000_000;
            case "m" -> 60 * 1_000_000_000.0;
            case "h" -> 3600 * 1_000_000_000.0;
            default -> throw new IllegalArgumentException("unknown unit " + unit);
        };
    }

    /**
     * Prints like Go: {@code 6h0m0s}, {@code 1h30m0s}, {@code 45m0s}, {@code 5s}.
     * Whole seconds only, which is all the seeder's output needs; fractions of a
     * second are rounded away.
     */
    static String format(Duration d) {
        long totalSeconds = Math.round(d.toMillis() / 1000.0);
        if (totalSeconds == 0) {
            return "0s";
        }
        String sign = totalSeconds < 0 ? "-" : "";
        totalSeconds = Math.abs(totalSeconds);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return sign + hours + "h" + minutes + "m" + seconds + "s";
        }
        if (minutes > 0) {
            return sign + minutes + "m" + seconds + "s";
        }
        return sign + seconds + "s";
    }
}
