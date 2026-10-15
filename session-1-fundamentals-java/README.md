# Session 1: Fundamentals — Java Edition

A Java 25 + Gradle mirror of [`../session-1-fundamentals/`](../session-1-fundamentals),
using the OpenTelemetry Java SDK directly with as few external dependencies as
possible.

## Layout

- `collector/` is reused from the Go version (see
  [`../session-1-fundamentals/collector`](../session-1-fundamentals/collector)).
  Start it first.
- [`otel-quickstart/`](otel-quickstart) — the live demo: a tiny HTTP service
  auto-instrumented by a small SDK-based wrapper, plus one hand-written custom
  span.
- [`seed-sample-service/`](seed-sample-service) — pre-session seeder for the
  `sample-service` dataset.
- [`instrumentation-traps/`](instrumentation-traps) — the three
  instrumentation traps from the slides, as before/after pairs.

## Requirements

- JDK 25+ (the build uses Gradle's Java toolchain configured to Java 25)
- The local Collector from `../session-1-fundamentals/collector/`

Common tasks:

```bash
# Build and test everything
./gradlew build

# Run the quickstart server
./gradlew :otel-quickstart:run

# Seed the sample-service dataset
./gradlew :seed-sample-service:run

# Run one instrumentation-trap demo
./gradlew :instrumentation-traps:run \
    -PmainClass=io.honeycomb.masterclass.instrumentationtraps.contextpropagation.Before
```

## Pre-session checklist

1. `cd ../session-1-fundamentals/collector && HONEYCOMB_API_KEY=<key> docker compose up -d`
2. `./gradlew :seed-sample-service:run` — backfills `sample-service`.
3. `./gradlew :otel-quickstart:run`, send a request, confirm a span lands under
   the `otel-quickstart` dataset.
