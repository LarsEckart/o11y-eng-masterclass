package io.honeycomb.masterclass.feedbackloops.seedcanaryregression;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads and prints durations the way Go does ("90m", "1h30m", "4h0m0s").
 *
 * <p>The Go seeder takes {@code SEED_WINDOW} and {@code SEED_DEPLOY_AGO} through
 * {@code time.ParseDuration}, and prints the window with {@code %s}. Java's own
 * {@link Duration#parse} wants ISO-8601 ("PT90M") and prints that same form.
 * The course docs and the Go README tell attendees to type "90m", so this
 * class keeps both ends of that contract the same in the Java version.</p>
 */
final class GoDuration {

    private GoDuration() {
    }

    /**
     * Parses Go duration syntax: an optional sign, then one or more
     * {@code <number><unit>} pairs, with units ns, us, µs, ms, s, m, h.
     * Returns empty on bad input, because the seeder ignores a bad value and
     * keeps its default (as the Go code does).
     */
    static Optional<Duration> parse(String s) {
        if (s == null || s.isEmpty()) {
            return Optional.empty();
        }
        boolean negative = false;
        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            negative = s.charAt(0) == '-';
            i++;
        }
        String rest = s.substring(i);
        if (rest.equals("0")) {
            return Optional.of(Duration.ZERO);
        }
        if (rest.isEmpty()) {
            return Optional.empty();
        }

        double totalNanos = 0;
        while (!rest.isEmpty()) {
            int n = 0;
            while (n < rest.length() && (Character.isDigit(rest.charAt(n)) || rest.charAt(n) == '.')) {
                n++;
            }
            if (n == 0) {
                return Optional.empty();
            }
            double number;
            try {
                number = Double.parseDouble(rest.substring(0, n));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            rest = rest.substring(n);

            int u = 0;
            while (u < rest.length() && rest.charAt(u) != '.' && !Character.isDigit(rest.charAt(u))) {
                u++;
            }
            if (u == 0) {
                return Optional.empty(); // number without a unit
            }
            double unitNanos = switch (rest.substring(0, u)) {
                case "ns" -> 1;
                case "us", "µs", "μs" -> 1_000;
                case "ms" -> 1_000_000;
                case "s" -> 1_000_000_000;
                case "m" -> 60_000_000_000.0;
                case "h" -> 3_600_000_000_000.0;
                default -> -1;
            };
            if (unitNanos < 0) {
                return Optional.empty();
            }
            rest = rest.substring(u);
            totalNanos += number * unitNanos;
        }

        var d = Duration.ofNanos((long) totalNanos);
        return Optional.of(negative ? d.negated() : d);
    }

    /**
     * Formats like Go's {@code Duration.String()}: "4h0m0s", "1m30s", "1.5s",
     * "300ms". Only the cases this seeder prints need to be right, but the
     * sub-second ones are cheap, so they are here too.
     */
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
