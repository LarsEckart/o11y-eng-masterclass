package io.honeycomb.masterclass.businesscase;

import io.honeycomb.masterclass.businesscase.BusinessCase.Case;
import io.honeycomb.masterclass.businesscase.BusinessCase.Inputs;
import io.honeycomb.masterclass.businesscase.BusinessCase.LineItem;
import io.honeycomb.masterclass.businesscase.BusinessCase.Scenario;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Prints the one-page argument Masterclass 3's lab asks you to build, from your
 * own numbers. This is the Java port of Go's {@code cmd/business-case}.
 *
 * <pre>
 * ./gradlew :business-case:run --args="\
 *   -engineers 120 -loaded-cost 260000 \
 *   -incidents-per-month 14 -mttr-hours 3.5 -responders 3 \
 *   -escalation-rate 0.45 -escalation-extra 2 \
 *   -unplanned-share 0.22 -observability-spend 350000"
 * </pre>
 *
 * <p>Run it with no flags to see the defaults it would use and a warning that
 * they are placeholders, not a template you should present.
 */
public final class BusinessCaseCli {

    private static final String RULE = "=".repeat(75);

    private BusinessCaseCli() {}

    public static void main(String[] args) {
        // The output has box-drawing characters. System.out uses the terminal's
        // locale, which can be plain ASCII (e.g. in CI), so we write UTF-8
        // ourselves to make the output the same everywhere.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int status = run(args, out, err);
        out.flush();
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * The whole program, with its streams passed in so tests can capture the
     * output. Returns the process exit status.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Inputs.Builder in = Inputs.builder();
        boolean[] showSources = {false};

        // Go's `flag` package registers each flag with a pointer to the field it
        // fills. Java has no pointers, so each flag carries a setter instead.
        List<Flag> flags = List.of(
                intFlag("engineers", "engineers in scope", in::engineers),
                floatFlag("loaded-cost", "annual fully-loaded cost per engineer (ask finance; it is well above base salary)", in::loadedCostPerEngineer),
                floatFlag("incidents-per-month", "incidents per month that pull people off planned work", in::incidentsPerMonth),
                floatFlag("mttr-hours", "mean wall-clock hours from noticed to resolved", in::meanTimeToResolveHours),
                floatFlag("responders", "people engaged per incident on average", in::respondersPerIncident),
                floatFlag("escalation-rate", "share of incidents that must be escalated (0-1)", in::escalationRate),
                floatFlag("escalation-extra", "additional people an escalation pulls in", in::escalationExtraResponders),
                floatFlag("unplanned-share", "share of engineering time on unplanned work and rework (0-1)", in::unplannedWorkShare),
                floatFlag("revenue-per-hour", "revenue exposed per hour of degradation; leave at 0 unless you can defend it", in::revenuePerHourAtRisk),
                floatFlag("observability-spend", "current annual observability spend", in::observabilityAnnualSpend),
                boolFlag("sources", "print the public figures Chapter 26 cites, with attribution", v -> showSources[0] = v));

        int parsed = parseFlags(args, flags, err);
        if (parsed == HELP) {
            return 0;
        }
        if (parsed != OK) {
            return parsed;
        }

        if (showSources[0]) {
            printSources(out);
            return 0;
        }

        Inputs inputs = in.build();
        boolean usingPlaceholders = inputs.engineers() == 0;
        if (usingPlaceholders) {
            inputs = placeholders();
        }

        Case c = BusinessCase.build(inputs);
        print(out, c, usingPlaceholders);
        return 0;
    }

    /**
     * Placeholders exist so the tool does something useful when run with no
     * flags, but the output says loudly that they are not your numbers. Chapter
     * 27's point is that the diagnosis has to rest on evidence; a borrowed set
     * of inputs is the opposite of that.
     */
    static Inputs placeholders() {
        return Inputs.builder()
                .engineers(100)
                .loadedCostPerEngineer(260000)
                .incidentsPerMonth(10)
                .meanTimeToResolveHours(4)
                .respondersPerIncident(3)
                .escalationRate(0.5)
                .escalationExtraResponders(2)
                .unplannedWorkShare(0.20)
                .observabilityAnnualSpend(400000)
                .build();
    }

    private static void print(PrintStream out, Case c, boolean usingPlaceholders) {
        if (usingPlaceholders) {
            out.print("""

                    ┌───────────────────────────────────────────────────────────────────────────┐
                    │  THESE ARE PLACEHOLDER NUMBERS. Do not present this.                      │
                    │  Rerun with your own -engineers, -incidents-per-month, -mttr-hours, and    │
                    │  -unplanned-share. The whole point of the exercise is that the figures     │
                    │  are yours and therefore hard to dismiss.                                 │
                    └───────────────────────────────────────────────────────────────────────────┘
                    """);
        }

        out.print("\nCOST OF THE CURRENT STATE\n" + RULE + "\n");
        for (LineItem it : c.currentState()) {
            out.print("\n  %-38s $%s / yr\n".formatted(it.label(), BusinessCase.formatMoney(it.annual())));
            out.print("  %s\n".formatted(it.formula()));
        }
        out.print("\n  %-38s $%s / yr\n".formatted("TOTAL", BusinessCase.formatMoney(c.currentTotal())));

        out.print("\n\nWHAT IMPROVEMENT WOULD BE WORTH\n" + RULE + "\n");
        // The closing delimiter sits left of the text, so the two-space
        // indent in the output survives Java's text-block indent stripping.
        out.print("""

                  The percentages below are assumptions you are choosing, not findings. Say so
                  out loud when you present this. A case that only works at its optimistic end
                  is not a case.

                """);
        out.print("  %-14s %-22s %14s %14s\n".formatted("Scenario", "Assumes", "Annual saving", "Net of spend"));
        out.print("  %s\n".formatted("-".repeat(68)));
        for (Scenario s : c.scenarios()) {
            String assumes = "-%s%% MTTR, -%s%% rework".formatted(
                    BusinessCase.fixed(s.mttrReduction() * 100, 0),
                    BusinessCase.fixed(s.unplannedWorkReduction() * 100, 0));
            out.print("  %-14s %-22s %14s %14s\n".formatted(
                    s.name(),
                    assumes,
                    "$" + BusinessCase.formatMoney(s.annualSaving()),
                    "$" + BusinessCase.formatMoney(c.netOf(s))));
        }

        if (c.inputs().observabilityAnnualSpend() > 0) {
            out.print("\n  Current observability spend: $%s / yr\n"
                    .formatted(BusinessCase.formatMoney(c.inputs().observabilityAnnualSpend())));
        }

        if (c.inputs().revenuePerHourAtRisk() == 0) {
            out.print("""

                      No revenue-at-risk figure was supplied, so this case rests on engineering
                      cost alone. That is usually the stronger ground: it is auditable, and nobody
                      has to agree with your model of customer behaviour to accept it.
                    """);
        }

        out.print("""

                WHAT THIS DOES NOT DO
                %s

                  It does not tell you whether observability caused an improvement. That is
                  what the five tests in Chapter 28 are for, and why you rerun them after the
                  work rather than declaring victory from a spreadsheet.

                  Run 'business-case -sources' for the public figures Chapter 26 cites on the
                  link between latency and revenue.

                """.formatted(RULE));
    }

    private static void printSources(PrintStream out) {
        out.print("""

                PUBLIC FIGURES CITED IN CHAPTER 26
                ===========================================================================

                These are the numbers the book actually cites for the link between latency
                and business outcomes. Use them as evidence that the relationship is real and
                measurable at scale — not as a multiplier to apply to your own revenue.

                  Amazon    every 100ms of added page load time cost ~1% in revenue
                            Steven van Vessum, Conductor, June 14 2023

                  Walmart   ~2% increase in conversions for every second shaved off load time
                            Cedric Moore, Trueform, February 10 2025

                  Staples   ~10% increase in conversions from a 1s home page improvement
                            Martyna Kozlowska, Reffine

                Chapter 26 is explicit that "turning this into monetary estimates is harder to
                give guidance on." Quoting someone else's conversion lift as though it were
                your forecast is how a business case loses a room. Cite these to establish
                that latency has measurable financial consequences, then argue your own case
                from your own numbers.

                """);
    }

    // ---------------------------------------------------------------------
    // A small command-line flag parser.
    //
    // Go's standard library has the `flag` package; the JDK has nothing like it.
    // Rather than add a library (picocli, commons-cli) for eleven flags, this
    // copies the handful of behaviours of Go's `flag` that the demo commands
    // rely on: single or double dash, "-name value" or "-name=value", bare
    // boolean flags, and a usage message on error or -h.
    // ---------------------------------------------------------------------

    private static final int OK = 0;
    private static final int HELP = -1;
    /** Go's flag package exits with status 2 on a bad command line. */
    private static final int BAD_USAGE = 2;

    /**
     * One command-line flag.
     *
     * @param typeName shown in the usage text; empty for boolean flags.
     * @param setter receives the raw text; throws IllegalArgumentException when
     *     it cannot be parsed.
     */
    private record Flag(String name, String typeName, String usage, Consumer<String> setter) {
        boolean isBool() {
            return typeName.isEmpty();
        }
    }

    private static Flag intFlag(String name, String usage, Consumer<Integer> target) {
        return new Flag(name, "int", usage, text -> target.accept(Integer.parseInt(text)));
    }

    private static Flag floatFlag(String name, String usage, Consumer<Double> target) {
        return new Flag(name, "float", usage, text -> target.accept(Double.parseDouble(text)));
    }

    private static Flag boolFlag(String name, String usage, Consumer<Boolean> target) {
        return new Flag(name, "", usage, text -> {
            switch (text) {
                case "1", "t", "T", "true", "TRUE", "True" -> target.accept(true);
                case "0", "f", "F", "false", "FALSE", "False" -> target.accept(false);
                default -> throw new IllegalArgumentException(text);
            }
        });
    }

    /** Returns {@link #OK}, {@link #HELP}, or {@link #BAD_USAGE}. */
    private static int parseFlags(String[] args, List<Flag> flags, PrintStream err) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // The first non-flag argument ends flag parsing, as in Go.
            if (arg.length() < 2 || arg.charAt(0) != '-') {
                break;
            }
            String body = arg.substring(arg.charAt(1) == '-' ? 2 : 1);
            if (body.isEmpty()) { // a bare "--" ends flag parsing
                break;
            }
            if (body.startsWith("-") || body.startsWith("=")) {
                err.println("bad flag syntax: " + arg);
                printUsage(flags, err);
                return BAD_USAGE;
            }

            String name = body;
            String value = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                name = body.substring(0, eq);
                value = body.substring(eq + 1);
            }

            if (name.equals("h") || name.equals("help")) {
                printUsage(flags, err);
                return HELP;
            }

            String flagName = name;
            Flag flag = flags.stream().filter(f -> f.name().equals(flagName)).findFirst().orElse(null);
            if (flag == null) {
                err.println("flag provided but not defined: -" + name);
                printUsage(flags, err);
                return BAD_USAGE;
            }

            if (value == null) {
                if (flag.isBool()) {
                    value = "true";
                } else if (i + 1 < args.length) {
                    value = args[++i];
                } else {
                    err.println("flag needs an argument: -" + name);
                    printUsage(flags, err);
                    return BAD_USAGE;
                }
            }

            try {
                flag.setter().accept(value);
            } catch (IllegalArgumentException e) {
                err.println("invalid value \"" + value + "\" for flag -" + name + ": parse error");
                printUsage(flags, err);
                return BAD_USAGE;
            }
        }
        return OK;
    }

    /** Same layout as Go's default usage: flags sorted by name, usage on the next line. */
    private static void printUsage(List<Flag> flags, PrintStream err) {
        err.println("Usage of business-case:");
        List<Flag> sorted = new ArrayList<>(flags);
        sorted.sort(Comparator.comparing(Flag::name));
        for (Flag f : sorted) {
            err.println("  -" + f.name() + (f.isBool() ? "" : " " + f.typeName()));
            err.println("    \t" + f.usage());
        }
    }
}
