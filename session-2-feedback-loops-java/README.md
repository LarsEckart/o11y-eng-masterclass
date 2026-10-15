# Session 2: Feedback Loops — Java Edition

A Java 25 + Gradle mirror of [`../session-2-feedback-loops/`](../session-2-feedback-loops),
using the OpenTelemetry Java SDK directly. Read the Go README for the session
story, the live demo run order, and the async lab:
[`../session-2-feedback-loops/README.md`](../session-2-feedback-loops/README.md).

The scenario is the same: a canary deploy that is ~40% slower, but only for
enterprise users on `/api/billing`. Service names, span names, attribute keys,
error rates, timings, and `SEED_*` variables match the Go seeder, so the
Honeycomb queries, deploy marker, and board work on Java-seeded data unchanged.

## What is here

| Go file | Java class |
| --- | --- |
| [`cmd/seed-canary-regression/main.go`](../session-2-feedback-loops/cmd/seed-canary-regression/main.go) | [`SeedCanaryRegression`](seed-canary-regression/src/main/java/io/honeycomb/masterclass/feedbackloops/seedcanaryregression/SeedCanaryRegression.java) |
| `time.ParseDuration` and `Duration.String()` (Go standard library) | [`GoDuration`](seed-canary-regression/src/main/java/io/honeycomb/masterclass/feedbackloops/seedcanaryregression/GoDuration.java) |
| `ForceFlush` returning export errors (Go SDK) | [`FailureTrackingExporter`](seed-canary-regression/src/main/java/io/honeycomb/masterclass/feedbackloops/seedcanaryregression/FailureTrackingExporter.java) |
| [`cmd/seed-canary-regression/delivery_test.go`](../session-2-feedback-loops/cmd/seed-canary-regression/delivery_test.go) | [`DeliveryTest`](seed-canary-regression/src/test/java/io/honeycomb/masterclass/feedbackloops/seedcanaryregression/DeliveryTest.java) |
| [`internal/scenario/scenario.go`](../session-2-feedback-loops/internal/scenario/scenario.go) | [`Scenario`](scenario/src/main/java/io/honeycomb/masterclass/feedbackloops/scenario/Scenario.java) (generator and the tuning comment), [`Config`](scenario/src/main/java/io/honeycomb/masterclass/feedbackloops/scenario/Config.java), [`Request`](scenario/src/main/java/io/honeycomb/masterclass/feedbackloops/scenario/Request.java), [`Step`](scenario/src/main/java/io/honeycomb/masterclass/feedbackloops/scenario/Step.java), [`Route`](scenario/src/main/java/io/honeycomb/masterclass/feedbackloops/scenario/Route.java) |
| [`internal/scenario/scenario_test.go`](../session-2-feedback-loops/internal/scenario/scenario_test.go) | [`ScenarioTest`](scenario/src/test/java/io/honeycomb/masterclass/feedbackloops/scenario/ScenarioTest.java) |

Two Gradle subprojects:

- [`scenario/`](scenario) — the scenario generator. Plain Java, no OpenTelemetry
  dependency, like Go's `internal/scenario`.
- [`seed-canary-regression/`](seed-canary-regression) — the seeder program.

`GoDuration` and `FailureTrackingExporter` have no Go file because Go gives you
that behaviour for free. Their class comments explain the gap.

Not copied here, because it does not change: the Terraform, the deploy-marker
script, and the worksheet. Use them from the Go folder:

- [`../session-2-feedback-loops/honeycomb-setup/`](../session-2-feedback-loops/honeycomb-setup)
- [`../session-2-feedback-loops/chapter2-ladder-worksheet.md`](../session-2-feedback-loops/chapter2-ladder-worksheet.md)

## Requirements

- JDK 25+ (the build uses Gradle's Java toolchain, set to Java 25)
- The local Collector from
  [`../session-1-fundamentals/collector`](../session-1-fundamentals/collector)

## Build, test, run

```bash
# Compile (with -Werror) and run all tests
./gradlew build

# Seed the dataset
./gradlew :seed-canary-regression:run

# Tune it, like the Go version
SEED_COUNT=20000 SEED_WINDOW=6h SEED_DEPLOY_AGO=2h ./gradlew :seed-canary-regression:run
```

`SEED_WINDOW` and `SEED_DEPLOY_AGO` take Go duration syntax (`90m`, `1h30m`,
`4h`). A value that does not parse is ignored and the default stays, as in Go.

`OTEL_EXPORTER_OTLP_ENDPOINT` defaults to `localhost:4317`. A bare `host:port`
and a full `http://host:port` URL both work.

## Pre-session checklist

1. Start the Collector:

   ```bash
   cd ../session-1-fundamentals/collector
   HONEYCOMB_API_KEY=<send-events key> docker compose up -d
   ```

2. Seed the dataset and note the deploy timestamp it prints:

   ```bash
   ./gradlew :seed-canary-regression:run
   ```

3. Create the deploy marker at that timestamp. The seeder prints the command;
   the script lives in
   [`../session-2-feedback-loops/honeycomb-setup/`](../session-2-feedback-loops/honeycomb-setup).

4. Confirm in Honeycomb: 10,000 root spans across the window, both
   `service.version` values present, and a visible second latency band on
   `/api/billing` after the marker.

## Go to Java: what differs

- **Config is immutable.** Go sets fields on a struct. The Java `Config` is a
  record, so `SEED_*` overrides use `withTraceCount(...)` and friends, and
  `Config.defaults(now)` takes the clock value up front.
- **Random numbers.** Both seed from the clock when run for real. Java's
  `Random` yields a different stream than Go's for the same seed, so the tests
  use seed 7 with Java's generator. They assert statistical bands, not exact
  values.
- **No `ctx` parameter.** Go threads a `context.Context` through `emit` so
  child spans find their parent. Java keeps the current span in a thread-local
  `Context`. The seeder never makes the root span current, so it passes the
  parent to each child explicitly (`setParent`).
- **`ForceFlush` and failures.** Go's `ForceFlush` returns the export error. Java's
  `forceFlush` succeeds even when every export failed, so `FailureTrackingExporter`
  counts failures and the seeder stops, as the Go one does.
- **Delivery test.** Go runs `go run .` against an in-process gRPC server. Java
  runs the same main class in a new JVM against an in-process gRPC server. The
  JDK `HttpServer` cannot play that role because OTLP/gRPC needs HTTP/2. The
  server and the OTLP message types come from test-only dependencies
  (`grpc-netty-shaded`, `grpc-stub`, `grpc-protobuf`, `opentelemetry-proto`).
  `failsLoudlyWhenTheReceiverRejectsExports` has no Go twin. It points the seeder
  at a receiver that rejects every export and expects a non-zero exit and the
  "is the Collector up?" message. It fails if `FailureTrackingExporter` is removed.
- **Paths in the output.** The seeder prints `cd` paths relative to this
  directory, not to the Go `cmd/` folder.
