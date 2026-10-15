package io.honeycomb.masterclass.everydomain.seedcicdpipeline;

import io.honeycomb.masterclass.everydomain.cicdscenario.Config;
import io.honeycomb.masterclass.everydomain.cicdscenario.Run;
import io.honeycomb.masterclass.everydomain.cicdscenario.Scenario;
import io.honeycomb.masterclass.everydomain.cicdscenario.Task;
import io.honeycomb.masterclass.everydomain.cicdscenario.TaskResult;
import io.honeycomb.masterclass.everydomain.cicdscenario.TestCase;
import io.honeycomb.masterclass.everydomain.otlpexport.FailureTrackingExporter;
import io.honeycomb.masterclass.everydomain.otlpexport.OtlpExport;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Backfills the Masterclass 6 CI/CD dataset: a pipeline run many times over a
 * two-week window, with a step-change build-duration regression in the trailing
 * day and one intermittently failing test case. See {@link Scenario} for the
 * scenario and why its proportions are what they are.
 *
 * <p>Run this against the Collector from {@code session-1-fundamentals/collector},
 * same as every other session.</p>
 */
public final class SeedCicdPipeline {

    static final String SERVICE_NAME = "cicd-pipeline";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it, same rationale as sessions 4 and 5's constant of
     * the same name.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    /**
     * Spans sent per export call. Also named for the delivery test: one batch
     * can be in flight while the queue behind it is full, so the queue plus one
     * batch is the most the SDK can hold before it starts dropping.
     */
    static final int MAX_EXPORT_BATCH_SIZE = 1024;

    // The Go version uses semconv.CICDPipelineName() and friends from a
    // generated semconv package. The Java opentelemetry-semconv artifact only
    // holds stable conventions, and the CI/CD and VCS ones are still in
    // development, so they live in a separate incubating artifact. We use the
    // attribute names as plain strings instead of adding that dependency. The
    // delivery test checks the names on the wire, so a typo would still fail.
    private static final String PIPELINE_NAME = "cicd.pipeline.name";
    private static final String PIPELINE_RESULT = "cicd.pipeline.result";
    private static final String PIPELINE_TASK_NAME = "cicd.pipeline.task.name";
    private static final String PIPELINE_TASK_RUN_RESULT = "cicd.pipeline.task.run.result";
    private static final String VCS_CHANGE_ID = "vcs.change.id";
    private static final String VCS_REF_HEAD_NAME = "vcs.ref.head.name";

    private SeedCicdPipeline() {
    }

    public static void main(String[] args) {
        var exports = new FailureTrackingExporter(OtlpExport.create(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")));
        SdkTracerProvider provider = setupTracing(exports);
        // Go keeps the tracer in a package variable. Java passes it along
        // explicitly, which keeps the emit methods easy to call from a test.
        Tracer tracer = provider.get(SERVICE_NAME);

        Config cfg = Config.defaults(Instant.now());
        Integer count = parseInt(System.getenv("SEED_COUNT"));
        if (count != null) {
            cfg = cfg.withRunCount(count);
        }

        // Go seeds from the clock; new Random() does the same.
        var rng = new Random();
        List<Run> runs = Scenario.generate(cfg, rng);

        // Emit in chunks, flushing after each: same reasoning as every other
        // seeder in this repo. The BatchSpanProcessor silently drops anything
        // that overflows its queue, and this dataset emits many spans per run.
        final int chunk = 50;

        int regressed = 0;
        int failed = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            emitRun(tracer, run);
            if (run.regressed()) {
                regressed++;
            }
            if (run.result() == TaskResult.FAILURE) {
                failed++;
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exports, "flushing spans after " + (i + 1) + " runs");
            }
        }

        flush(provider, exports, "final flush");
        if (!provider.shutdown().join(30, TimeUnit.SECONDS).isSuccess()) {
            fatal("shutting down tracer provider failed");
        }

        // Go prints the time in the machine's local zone, as RFC 3339 without
        // fractional seconds. Same here.
        String regressionStart = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(cfg.regressionStart().truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));

        System.out.print("""

                seeded %d pipeline runs into service "%s"
                  build regression   started %s, still ongoing at seed time
                  regressed runs      %d
                  flaky test failures %d (out of %d runs) on %s

                The regression is anchored relative to now, so a trailing-window P95 trigger
                on the build task only finds it if you seed shortly before you go live.
                """.formatted(
                runs.size(), SERVICE_NAME,
                regressionStart,
                regressed,
                failed, runs.size(), Scenario.FLAKY_TEST_NAME));
    }

    /**
     * Blocks until the exporter has shipped everything queued so far, so the
     * caller can safely generate the next chunk.
     */
    private static void flush(SdkTracerProvider provider, FailureTrackingExporter exports, String what) {
        CompletableResultCode result = provider.forceFlush().join(30, TimeUnit.SECONDS);
        // forceFlush alone is not enough in Java; see FailureTrackingExporter.
        if (!result.isSuccess() || exports.failures() > 0) {
            fatal(what + ": flush failed or timed out "
                    + "(is the Collector up? see ../session-1-fundamentals/collector)");
        }
    }

    private static SdkTracerProvider setupTracing(FailureTrackingExporter exporter) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        return SdkTracerProvider.builder()
                // A queue well above the 2048 default, so a chunk never comes
                // close to overflowing it even if an export is briefly slow.
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                        .build())
                .setResource(resource)
                .build();
    }

    /**
     * Turns one generated run into a trace: a root pipeline span, four task
     * spans as its children, and the test task's test cases as its own children
     * in turn. Every span gets an explicit historical timestamp so the whole
     * two-week window can be backfilled in one pass, same as session 4's seeder.
     */
    static void emitRun(Tracer tracer, Run run) {
        Instant end = run.start();
        for (Task t : run.tasks()) {
            end = end.plus(t.duration());
        }

        Span root = tracer.spanBuilder("pipeline run")
                .setStartTimestamp(run.start())
                .startSpan();
        root.setAttribute(PIPELINE_NAME, "masterclass-app-ci");
        root.setAttribute("cicd.pipeline.run.id", run.runId());
        root.setAttribute(VCS_CHANGE_ID, Integer.toString(run.prNumber()));
        root.setAttribute(VCS_REF_HEAD_NAME, run.branch());
        if (run.result() == TaskResult.FAILURE) {
            root.setAttribute(PIPELINE_RESULT, TaskResult.FAILURE.value());
            root.setStatus(StatusCode.ERROR, "pipeline failed");
        } else {
            root.setAttribute(PIPELINE_RESULT, TaskResult.SUCCESS.value());
        }

        // Go: runCtx, root := tracer.Start(ctx, ...) hands back a new context
        // carrying the root span, and the children are started from it. Java
        // has no ctx parameter to thread through. The "current" span lives in a
        // thread-local Context, and we never make the root current (that would
        // need a try-with-resources scope per span), so we build a Context
        // that holds the root and name it as the parent explicitly.
        Context runContext = Context.root().with(root);

        Instant taskStart = run.start();
        for (Task task : run.tasks()) {
            emitTask(tracer, runContext, task, taskStart);
            taskStart = taskStart.plus(task.duration());
        }

        root.end(end);
    }

    private static void emitTask(Tracer tracer, Context parent, Task task, Instant start) {
        Span span = tracer.spanBuilder(task.name())
                .setParent(parent)
                .setStartTimestamp(start)
                .startSpan();
        span.setAttribute(PIPELINE_TASK_NAME, task.name());
        if (task.result() == TaskResult.FAILURE) {
            span.setAttribute(PIPELINE_TASK_RUN_RESULT, TaskResult.FAILURE.value());
            span.setStatus(StatusCode.ERROR, "task failed");
        } else {
            span.setAttribute(PIPELINE_TASK_RUN_RESULT, TaskResult.SUCCESS.value());
        }

        if (!task.tests().isEmpty()) {
            Context taskContext = parent.with(span);
            Instant testStart = start;
            // Integer division of the task's duration, like Go's
            // time.Duration division: the test cases tile the task evenly.
            Duration testDuration = task.duration().dividedBy(task.tests().size());
            for (TestCase tc : task.tests()) {
                emitTestCase(tracer, taskContext, tc, testStart, testDuration);
                testStart = testStart.plus(testDuration);
            }
        }

        span.end(start.plus(task.duration()));
    }

    private static void emitTestCase(Tracer tracer, Context parent, TestCase tc, Instant start, Duration duration) {
        Span span = tracer.spanBuilder(tc.name())
                .setParent(parent)
                .setStartTimestamp(start)
                .startSpan();
        span.setAttribute("test.name", tc.name());
        if (tc.result() == TaskResult.FAILURE) {
            span.setAttribute("error", true);
            span.setStatus(StatusCode.ERROR, "test failed");
        }
        span.end(start.plus(duration));
    }

    /** Go's {@code strconv.Atoi} with the error swallowed: null means "not a number". */
    private static Integer parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Go's {@code log.Fatalf}: print the reason and exit non-zero. */
    private static void fatal(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
