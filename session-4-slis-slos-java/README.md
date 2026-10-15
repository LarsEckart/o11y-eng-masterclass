# Session 4: SLIs and SLOs — Java Edition

A Java 25 + Gradle mirror of [`../session-4-slis-slos/`](../session-4-slis-slos),
using the OpenTelemetry Java SDK directly. Read the Go README for the session
story (the scenario, the free-tier caveat, the live demo run order, the async
lab): [`../session-4-slis-slos/README.md`](../session-4-slis-slos/README.md).

## Layout

- [`scenario/`](scenario) — the scenario generator: a plain library with no
  OpenTelemetry dependency. It decides which requests exist, who sent them, and
  which ones fail. The tuning arithmetic lives in its `package-info.java`.
- [`seed-sli-service/`](seed-sli-service) — the seeder. Turns each generated
  request into one root span with an explicit historical timestamp and ships it
  to the Collector.

Not copied here, because they do not change:

- The Honeycomb setup (derived column, saved queries, trigger, marker) is the
  Terraform in
  [`../session-4-slis-slos/honeycomb-setup/`](../session-4-slis-slos/honeycomb-setup).
  It queries fields this seeder emits, so the span names, attribute keys, and
  values here must stay identical to the Go version.
- The Collector is the one from
  [`../session-1-fundamentals/collector`](../session-1-fundamentals/collector).

## Go → Java

| Go | Java |
| --- | --- |
| `internal/scenario/scenario.go` (package doc) | `scenario/.../scenario/package-info.java` |
| `internal/scenario/scenario.go` (`Config`, `DefaultConfig`) | `scenario/.../scenario/Config.java` |
| `internal/scenario/scenario.go` (`Request`, `StatusCode`) | `scenario/.../scenario/Request.java` |
| `internal/scenario/scenario.go` (`Route`) | `scenario/.../scenario/Route.java` |
| `internal/scenario/scenario.go` (`Generate`, `versionFor`, `userTypeFor`, `jitter`) | `scenario/.../scenario/Generator.java` |
| `internal/scenario/scenario_test.go` | `scenario/.../scenario/ScenarioTest.java` |
| `cmd/seed-sli-service/main.go` | `seed-sli-service/.../seedsliservice/SeedSliService.java` |
| `cmd/seed-sli-service/delivery_test.go` | `seed-sli-service/.../seedsliservice/DeliveryTest.java` |
| `time.ParseDuration` / `Duration.String` (Go standard library) | `seed-sli-service/.../seedsliservice/GoDuration.java` (+ `GoDurationTest`) |
| `ForceFlush` returning the export error (Go SDK) | `seed-sli-service/.../seedsliservice/FailureTrackingExporter.java` |

All paths are under `src/main/java/io/honeycomb/masterclass/slisslos/` (or
`src/test/java/...` for tests).

## Requirements

- JDK 25+ (Gradle uses a Java 25 toolchain)
- The local Collector from `../session-1-fundamentals/collector/`

## Build, test, run

```bash
# Compile (with -Werror) and run all tests
./gradlew build

# Seed the sli-demo-service dataset
./gradlew :seed-sli-service:run

# Same tuning knobs as the Go version
SEED_COUNT=5000 SEED_WINDOW=3h SEED_INCIDENT_AGO=30m ./gradlew :seed-sli-service:run
```

Environment variables: `SEED_COUNT` (15000), `SEED_WINDOW` (6h),
`SEED_INCIDENT_AGO` (60m), `OTEL_EXPORTER_OTLP_ENDPOINT` (`localhost:4317`).
Durations use Go syntax such as `90m` or `1h30m`. Like Go, the seeder speaks
OTLP over gRPC only. A bare `host:port` endpoint works, as in Go; the Java
version adds the `http://` the exporter wants.

The delivery test runs the seeder in its own JVM, like Go's `go run .`, and
catches spans on a real OTLP/gRPC server (`grpc-netty-shaded`, test scope
only). Gradle passes it the seeder's classpath in the `seeder.classpath`
system property.

## Pre-session checklist

1. `cd ../session-1-fundamentals/collector && HONEYCOMB_API_KEY=<key> docker compose up -d`
2. `./gradlew :seed-sli-service:run`, shortly before you go live. The incident
   is anchored to "now" at seed time and is still ongoing when seeding
   finishes; a seed from yesterday reads as history, not a current burn.
3. Apply the Terraform in
   [`../session-4-slis-slos/honeycomb-setup/`](../session-4-slis-slos/honeycomb-setup).

## Differences from Go worth knowing

- **Random numbers.** The generator draws in the same order and with the same
  distributions, but Go's `math/rand` and Java's `java.util.Random` are
  different algorithms, so a given seed does not give the same dataset. The
  tests check properties (rates, populations, trigger behaviour), not exact
  values, and they pass across 200 seeds.
- **Tracer passing.** The Go seeder registers a global tracer provider and
  reads it from a package variable. The Java one passes the `Tracer` around,
  because Java's global can be set only once per JVM.
- **Flush errors.** Go's `ForceFlush` returns the exporter's error. Java's
  reports success even when the export failed, so `FailureTrackingExporter`
  counts failures and the seeder turns them into a non-zero exit.
