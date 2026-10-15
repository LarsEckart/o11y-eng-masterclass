# Session 6: Observability in Every Domain — Java Edition

A Java 25 + Gradle mirror of [`../session-6-every-domain/`](../session-6-every-domain),
using the OpenTelemetry Java SDK directly. Read the Go README for the session
story, the three demos, the live run order, and the async lab:
[`../session-6-every-domain/README.md`](../session-6-every-domain/README.md).

This folder ports the three seeders: CI/CD pipeline, performance service, and
LLM eval pipeline. Service names, span names, attribute keys and values,
timings, error rates, and `SEED_COUNT` match the Go seeders, so the Terraform
queries, trigger, and marker work on Java-seeded data unchanged.

Not copied here, because it does not change: the Terraform, the Honeycomb
setup notes, and the CircleCI and Claude Code material. Use them from the Go
folder:

- [`../session-6-every-domain/honeycomb-setup/`](../session-6-every-domain/honeycomb-setup)
  (README and Terraform)
- [`../session-6-every-domain/README.md`](../session-6-every-domain/README.md)
  (the real Claude Code telemetry demo and this repo's own CircleCI trace)

## What is here

| Go file | Java class |
| --- | --- |
| [`cmd/seed-cicd-pipeline/main.go`](../session-6-every-domain/cmd/seed-cicd-pipeline/main.go) | [`SeedCicdPipeline`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/seedcicdpipeline/SeedCicdPipeline.java) |
| [`cmd/seed-cicd-pipeline/delivery_test.go`](../session-6-every-domain/cmd/seed-cicd-pipeline/delivery_test.go) | [`DeliveryTest`](seed-cicd-pipeline/src/test/java/io/honeycomb/masterclass/everydomain/seedcicdpipeline/DeliveryTest.java) |
| [`internal/cicdscenario/scenario.go`](../session-6-every-domain/internal/cicdscenario/scenario.go) | [`Scenario`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/Scenario.java) (generator and the tuning comment), plus its types: [`Config`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/Config.java), [`Run`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/Run.java), [`Task`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/Task.java), [`TestCase`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/TestCase.java), [`TaskResult`](seed-cicd-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/cicdscenario/TaskResult.java) |
| [`internal/cicdscenario/scenario_test.go`](../session-6-every-domain/internal/cicdscenario/scenario_test.go) | [`ScenarioTest`](seed-cicd-pipeline/src/test/java/io/honeycomb/masterclass/everydomain/cicdscenario/ScenarioTest.java) |
| [`cmd/seed-perf-service/main.go`](../session-6-every-domain/cmd/seed-perf-service/main.go) | [`SeedPerfService`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/seedperfservice/SeedPerfService.java) |
| `Duration.String()` (Go standard library) | [`GoDuration`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/seedperfservice/GoDuration.java) |
| [`cmd/seed-perf-service/delivery_test.go`](../session-6-every-domain/cmd/seed-perf-service/delivery_test.go) | [`DeliveryTest`](seed-perf-service/src/test/java/io/honeycomb/masterclass/everydomain/seedperfservice/DeliveryTest.java) |
| [`internal/perfscenario/scenario.go`](../session-6-every-domain/internal/perfscenario/scenario.go) | [`Scenario`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/perfscenario/Scenario.java) (generator, percentile, and the tuning comment), plus its types: [`Config`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/perfscenario/Config.java), [`Request`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/perfscenario/Request.java), [`QueryTemplate`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/perfscenario/QueryTemplate.java), [`QueryKind`](seed-perf-service/src/main/java/io/honeycomb/masterclass/everydomain/perfscenario/QueryKind.java) |
| [`internal/perfscenario/scenario_test.go`](../session-6-every-domain/internal/perfscenario/scenario_test.go) | [`ScenarioTest`](seed-perf-service/src/test/java/io/honeycomb/masterclass/everydomain/perfscenario/ScenarioTest.java) |
| [`cmd/seed-llm-eval-pipeline/main.go`](../session-6-every-domain/cmd/seed-llm-eval-pipeline/main.go) | [`SeedLlmEvalPipeline`](seed-llm-eval-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/seedllmevalpipeline/SeedLlmEvalPipeline.java) |
| [`cmd/seed-llm-eval-pipeline/delivery_test.go`](../session-6-every-domain/cmd/seed-llm-eval-pipeline/delivery_test.go) | [`DeliveryTest`](seed-llm-eval-pipeline/src/test/java/io/honeycomb/masterclass/everydomain/seedllmevalpipeline/DeliveryTest.java) |
| [`internal/llmscenario/scenario.go`](../session-6-every-domain/internal/llmscenario/scenario.go) | [`Scenario`](seed-llm-eval-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/llmscenario/Scenario.java), plus [`Config`](seed-llm-eval-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/llmscenario/Config.java), [`Conversation`](seed-llm-eval-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/llmscenario/Conversation.java), [`Range`](seed-llm-eval-pipeline/src/main/java/io/honeycomb/masterclass/everydomain/llmscenario/Range.java) |
| [`internal/llmscenario/scenario_test.go`](../session-6-every-domain/internal/llmscenario/scenario_test.go) | [`ScenarioTest`](seed-llm-eval-pipeline/src/test/java/io/honeycomb/masterclass/everydomain/llmscenario/ScenarioTest.java) |
| [`internal/otlpexport/otlpexport.go`](../session-6-every-domain/internal/otlpexport/otlpexport.go) | [`OtlpExport`](otlp-export/src/main/java/io/honeycomb/masterclass/everydomain/otlpexport/OtlpExport.java) |
| `ForceFlush` returning export errors (Go SDK) | [`FailureTrackingExporter`](otlp-export/src/main/java/io/honeycomb/masterclass/everydomain/otlpexport/FailureTrackingExporter.java) |
| [`internal/otlpexport/otlpexport_test.go`](../session-6-every-domain/internal/otlpexport/otlpexport_test.go) | [`OtlpExportTest`](otlp-export/src/test/java/io/honeycomb/masterclass/everydomain/otlpexport/OtlpExportTest.java) |
| the `collector` type and `runSeeder` helper repeated in each `delivery_test.go` | [`FakeCollector`](otlp-test-support/src/main/java/io/honeycomb/masterclass/everydomain/testsupport/FakeCollector.java), [`SeederProcess`](otlp-test-support/src/main/java/io/honeycomb/masterclass/everydomain/testsupport/SeederProcess.java), [`ReceivedSpan`](otlp-test-support/src/main/java/io/honeycomb/masterclass/everydomain/testsupport/ReceivedSpan.java) |

Gradle subprojects:

- [`seed-cicd-pipeline/`](seed-cicd-pipeline), [`seed-perf-service/`](seed-perf-service),
  [`seed-llm-eval-pipeline/`](seed-llm-eval-pipeline) — one per Go `cmd/`
  program. Each also holds the scenario package only it uses, as a separate Java
  package with no OpenTelemetry in it, like Go's `internal/*scenario`.
- [`otlp-export/`](otlp-export) — shared by all three seeders: the exporter
  factory and `FailureTrackingExporter`.
- [`otlp-test-support/`](otlp-test-support) — test code only: a real gRPC
  receiver and a helper that runs a seeder in its own JVM. Go copies this into
  every `delivery_test.go`; Java keeps one copy.

`GoDuration` and `FailureTrackingExporter` have no Go file because Go gives you
that behaviour for free. Their class comments explain the gap.

## Requirements

- JDK 25+ (the build uses Gradle's Java toolchain, set to Java 25)
- The local Collector from
  [`../session-1-fundamentals/collector`](../session-1-fundamentals/collector)

## Build, test, run

```bash
# Compile (with -Werror) and run all tests
./gradlew build

# Seed the three datasets
./gradlew :seed-cicd-pipeline:run
./gradlew :seed-perf-service:run       # note the printed migration timestamp
./gradlew :seed-llm-eval-pipeline:run

# Tune the volume, like the Go version
SEED_COUNT=100 ./gradlew :seed-cicd-pipeline:run
```

`SEED_COUNT` defaults to 500 runs, 24000 requests, and 5000 conversations. Each
program reads it the way Go does: a value that does not parse is ignored and the
default stays. The other proportions live in each scenario's `Config.defaults`.

`OTEL_EXPORTER_OTLP_ENDPOINT` defaults to `localhost:4317`. A bare `host:port`
and a full `http://host:port` URL both work. With nothing listening, a seeder
stops with "is the Collector up?" and a non-zero exit code.

The delivery tests start each seeder in its own JVM, so `./gradlew build` takes a
few seconds longer than the unit tests alone would.

## Pre-session checklist

1. Start the Collector:

   ```bash
   cd ../session-1-fundamentals/collector
   HONEYCOMB_API_KEY=<send-events key> docker compose up -d
   ```

2. Seed all three datasets with the commands above.
3. Apply the Terraform from
   [`../session-6-every-domain/honeycomb-setup`](../session-6-every-domain/honeycomb-setup).
4. Confirm in Honeycomb, as in the
   [Go checklist](../session-6-every-domain/README.md#pre-session-checklist).

## Where Java differs from Go

- **Random numbers.** Go's `math/rand` and Java's `Random` are different
  generators, so a fixed seed gives different data. The scenario tests check
  the shape of the data (clusters, rates, step change), not exact values. The
  perf test uses a different fixed seed than Go for that reason; see its
  comment.
- **Context passing.** Go's `tracer.Start(ctx, ...)` returns a new `ctx` that
  carries the span to its children. Java has no `ctx` parameter: the current
  span lives in a thread-local `Context`. The seeders name the parent
  explicitly with `setParent(Context.root().with(parentSpan))`.
- **Semantic-convention names.** Go imports `semconv` for CI/CD, VCS, and host
  attributes. The Java semconv artifact has only stable names, so those keys
  are string constants here. The delivery tests check them on the wire.
- **Flush errors.** Go's `ForceFlush` returns export failures; the Java one does
  not. `FailureTrackingExporter` closes that gap.
- **Exponential draws.** Go has `rng.ExpFloat64()`; the Java long-tail template
  uses `-ln(1 - U)`, the same distribution.
- **Delivery test tuning.** Go seeds 350 runs (cicd) or 9000 spans (perf, llm).
  The Java tests seed 400 runs and 10000 spans. The Java SDK can hold the
  queue (8192) plus one batch in flight (1024), so the test must beat 9216
  spans to see a dropped one. The fake receiver also waits 20 ms per export so
  the check does not depend on machine speed.
