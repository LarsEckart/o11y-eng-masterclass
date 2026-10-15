# Session 3: The Business Case and the Investment Diagnostic — Java Edition

A Java 25 + Gradle mirror of [`../session-3-business-case/`](../session-3-business-case).
Read the Go README for the session story, the five tests, the pre-session
checklist, the live demo run order and the async lab:
[`../session-3-business-case/README.md`](../session-3-business-case/README.md).

The worksheet is the centre of this session and is not duplicated here. Use it
from the Go folder:
[`../session-3-business-case/five-tests-worksheet.md`](../session-3-business-case/five-tests-worksheet.md).

## Layout

- [`business-case/`](business-case) — the CLI that turns your own numbers into a
  one-page argument. It does plain arithmetic and never uses OpenTelemetry, so
  it has no OTel dependency.
- [`seed-arbitrary-question/`](seed-arbitrary-question) — seeds a week of
  genuinely wide checkout events (the `checkout-web` dataset), so the Arbitrary
  Question Test can be run live. The event generator lives inside this
  subproject because nothing else uses it.

The Collector from
[`../session-1-fundamentals/collector`](../session-1-fundamentals/collector) is
reused as-is.

## Go to Java map

| Go | Java |
|---|---|
| `cmd/business-case/main.go` | `business-case/.../businesscase/BusinessCaseCli.java` |
| `internal/businesscase/businesscase.go` | `business-case/.../businesscase/BusinessCase.java` |
| `internal/businesscase/businesscase_test.go` | `business-case/.../businesscase/BusinessCaseTest.java` |
| — (Go's `flag` package) | the small flag parser at the bottom of `BusinessCaseCli.java`, covered by `BusinessCaseCliTest.java` (new) |
| `cmd/seed-arbitrary-question/main.go` | `seed-arbitrary-question/.../seedarbitraryquestion/SeedArbitraryQuestion.java` |
| `cmd/seed-arbitrary-question/delivery_test.go` | `seed-arbitrary-question/.../seedarbitraryquestion/DeliveryTest.java` |
| `internal/wideevent/wideevent.go` | `.../wideevent/Event.java` (the record and the question's constants), `Config.java`, `Generator.java` |
| `internal/wideevent/attributes.go` | `.../wideevent/EventAttributes.java` |
| `internal/wideevent/wideevent_test.go` | `.../wideevent/GeneratorTest.java` |
| — (Go's `time.ParseDuration` and `Duration.String`) | `.../seedarbitraryquestion/GoDuration.java`, covered by `GoDurationTest.java` (new) |
| — | `EmitTest.java` (new): checks the span shape with `InMemorySpanExporter` |

Java package root: `io.honeycomb.masterclass.businesscase` and
`io.honeycomb.masterclass.seedarbitraryquestion`.

## Requirements

- JDK 25+ (the build uses Gradle's Java toolchain configured to Java 25)
- The local Collector from `../session-1-fundamentals/collector/` (seeder only)

## Build, test, run

```bash
# Build and test everything (compiles with -Werror)
./gradlew build

# Seed the checkout-web dataset (needs the Collector on localhost:4317)
./gradlew :seed-arbitrary-question:run

# Same, with a smaller or shorter dataset
SEED_COUNT=5000 SEED_WINDOW=72h ./gradlew :seed-arbitrary-question:run

# Business case with the placeholder numbers
./gradlew :business-case:run

# Business case with your own numbers
./gradlew :business-case:run --args="-engineers 120 -loaded-cost 260000 \
  -incidents-per-month 14 -mttr-hours 3.5 -responders 3 \
  -escalation-rate 0.45 -escalation-extra 2 \
  -unplanned-share 0.22 -observability-spend 350000"

# The public figures Chapter 26 cites
./gradlew :business-case:run --args="-sources"
```

The business-case output is byte for byte what the Go tool prints for the same
flags. Gradle prints its own progress lines too; add `-q` to hide them.

The seeder reads these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `localhost:4317` | Collector address (`host:port` or a full `http://` URL) |
| `SEED_COUNT` | `20000` | Number of events |
| `SEED_WINDOW` | `168h` | How far back to spread them, in Go duration syntax (`72h`, `1h30m`) |

## Pre-session checklist

1. `cd ../session-1-fundamentals/collector && HONEYCOMB_API_KEY=<key> docker compose up -d`
2. `./gradlew :seed-arbitrary-question:run`. It prints the filters and how many
   events match. Check that number is non-zero before going live.
3. Follow steps 3 and 4 of the
   [Go README checklist](../session-3-business-case/README.md#pre-session-checklist)
   (Activity Log queries and the business-case run).

The Terraform, the Honeycomb queries and the worksheet are shared with the Go
version; attribute names and values match it exactly, so nothing there changes.

## Where Java differs from Go

- **Random numbers.** Go's `rand.NewSource(11)` and Java's `new Random(11)` are
  different algorithms, so a fixed seed gives different events. The weights and
  ranges are identical, and the tests assert the same shares and thresholds.
  The seeder seeds from the clock anyway, as in Go.
- **Delivery test.** Same standard as the Go one. The test starts a real
  OTLP/gRPC server (`grpc-netty-shaded`, with the OTel proto classes to decode
  requests; all test-only dependencies) and runs the seeder as a separate JVM,
  where Go runs `go run .`. The build passes the seeder's classpath to the test
  in the `seeder.classpath` system property. The seeder speaks gRPC only, like
  the Go one. The test seeds 12,000 spans, more than the 8,192-span queue plus
  one 1,024-span batch in flight, and the receiver sleeps 20 ms per request, so
  a seeder that flushes only at the end loses spans and the test fails. Go
  runs the seeder once per test; Java runs it once and shares the result.
  A second test, `failsLoudlyWhenTheReceiverRejectsExports`, runs the seeder
  against a receiver that answers every export with `INTERNAL` and expects a
  non-zero exit, the "is the Collector up?" message and no "seeded" line. It
  has no Go counterpart; it guards the exporter wrapper described next.
- **Failed flushes.** Go's `ForceFlush` returns the exporter's error. Java's
  `forceFlush()` reports success even when every export failed, so the seeder
  wraps the exporter and counts failures. A dead Collector stops the seeder with
  exit 1, as in Go.
- **Context.** Go passes `ctx` to `tracer.Start`. Java reads the current context
  from a thread-local, so `emit` calls `setNoParent()` to say "root span".
- **Flags and durations.** The JDK has no `flag` package and no Go-style
  duration parser, so `BusinessCaseCli` and `GoDuration` implement the small
  parts the demos use.
