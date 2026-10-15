# Session 5: What Observability Costs — Java Edition

A Java 25 + Gradle mirror of [`../session-5-cost-sampling/`](../session-5-cost-sampling),
using the OpenTelemetry Java SDK directly. Read the Go
[README](../session-5-cost-sampling/README.md) for the session story, the
scenario, the live-demo run order and the pre-session checklist. This file only
covers what is different in Java.

The Honeycomb side is unchanged and is not copied here: the saved queries
live in [`../session-5-cost-sampling/honeycomb-setup`](../session-5-cost-sampling/honeycomb-setup),
and the Collector (with its `adaptive_tail_sampling` rules) is in
[`../session-1-fundamentals/collector`](../session-1-fundamentals/collector).
The Java seeder sends the same service name, span names, attribute keys and
values, so those queries work against either version.

## Layout

One Gradle subproject, because Go has one program and its only shared package
(`internal/scenario`) has no second user.

- [`seed-cost-sampling-service/`](seed-cost-sampling-service) — the seeder and
  the scenario it draws from.

### Go → Java

| Go | Java |
| --- | --- |
| `cmd/seed-cost-sampling-service/main.go` | `SeedCostSamplingService` (`run`, `emit`, `flush`, tracing setup) |
| `internal/scenario/scenario.go` | `scenario/Scenario` (`Route`, `Config`, `Request`, `defaultConfig`, `generate`) |
| `time.ParseDuration` / `Duration.String()` (stdlib) | `GoDuration` (no JDK equivalent for Go's `90s` / `1m30s` spelling) |
| `cmd/.../delivery_test.go` | `SeedCostSamplingServiceDeliveryTest` |
| `internal/scenario/scenario_test.go` | `scenario/ScenarioTest` |
| (none) | `GoDurationTest` |

All sources are under
`seed-cost-sampling-service/src/{main,test}/java/io/honeycomb/masterclass/costsampling/`.

## Requirements

- JDK 25+ (the build uses Gradle's Java toolchain set to Java 25)
- The local Collector from `../session-1-fundamentals/collector/`

## Build, test, run

```bash
# Compile (with -Werror) and run all tests
./gradlew build

# Seed the cost-sampling-service dataset (about two minutes)
./gradlew :seed-cost-sampling-service:run
```

Per the Go README's checklist: restart the Collector first so the sampler
starts cold, start the seeder no earlier than an hour before going live, and
seed only once inside the window.

Tuning uses the same environment variables as Go:

| Variable | Default | Meaning |
| --- | --- | --- |
| `SEED_COUNT` | `5000` | requests to emit (two spans each) |
| `SEED_DURATION` | `90s` | time to pace emission over; must span several of the Collector's `adjustment_interval`s |
| `SEED_SETTLE_WAIT` | `15s` | wait after the last flush for the Collector's `decision_delay` and `adjustment_interval` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `localhost:4317` | the Collector, plain text (a `http://` prefix is optional) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | Java-only: `http/protobuf` switches to OTLP over HTTP (the tests use it) |

Durations use Go's spelling (`90s`, `1m30s`, `10ms`), as before.

```bash
SEED_COUNT=500 SEED_DURATION=20s SEED_SETTLE_WAIT=5s ./gradlew :seed-cost-sampling-service:run
```

## Tests

`ScenarioTest` checks the generated data: route shares sum to 1 and show up in
the output, the 1% route stays visible, the error rate matches, and the
default `duration` spans at least four of the Collector's
`adjustment_interval`s (read from the real
`otel-collector-config.yaml`, so that file must exist at
`../session-1-fundamentals/collector/`).

`SeedCostSamplingServiceDeliveryTest` runs the real seeder against a fake OTLP
receiver (a JDK `HttpServer`) and checks every span arrives, each trace has a
root and a matching `db.query` child, the attribute keys are right, errored
traces carry error status on both spans, and emission is paced over
`SEED_DURATION`.
