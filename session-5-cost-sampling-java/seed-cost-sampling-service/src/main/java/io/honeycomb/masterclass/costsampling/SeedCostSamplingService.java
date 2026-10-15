package io.honeycomb.masterclass.costsampling;

import io.honeycomb.masterclass.costsampling.scenario.Scenario;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;

import java.io.PrintStream;
import java.util.Collection;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates the Masterclass 5 dataset: a mostly-healthy service with a skewed
 * route distribution and a synthetic 15% error rate, paced over real
 * wall-clock time (not backdated, unlike session 4's seeder) so session 1's
 * shared Collector actually makes a live tail-sampling decision on each trace
 * as it arrives. See {@link Scenario} for the scenario and why its
 * proportions are what they are, and
 * {@code ../session-1-fundamentals/collector/otel-collector-config.yaml} for
 * the adaptive_tail_sampling rules this dataset is built to exercise.
 *
 * <p>Run this against the Collector from session-1-fundamentals/collector,
 * same as every other session, restarting it first so the sampler starts
 * cold, and no earlier than an hour before going live so the saved queries'
 * 2-hour window still covers it when the demo runs.
 */
public final class SeedCostSamplingService {

    private SeedCostSamplingService() {}

    static final String SERVICE_NAME = "cost-sampling-service";

    /**
     * BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it: a test that seeds fewer spans than this cannot
     * detect a dropped-span regression, so it asserts against this value
     * rather than a hardcoded guess that would silently rot if this changed.
     * Same rationale as session 4's constant of the same name.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    /**
     * Spans per export call. Also needed by the delivery test: the batch worker
     * takes one batch off the queue before it starts exporting, so a spans
     * count has to beat MAX_QUEUE_SIZE plus this to overflow the queue.
     */
    static final int MAX_EXPORT_BATCH_SIZE = 1024;

    public static void main(String[] args) {
        try {
            run(System.getenv(), System.out);
        } catch (RuntimeException e) {
            // Go's log.Fatalf: print one line and exit non-zero.
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * The whole seeder. It takes its environment and output as arguments, not
     * from System.getenv() and System.out, which keeps {@link #main} a thin
     * wrapper. The delivery test does not call this directly: like Go's
     * {@code go run .}, it starts {@link #main} in a separate JVM.
     */
    static void run(Map<String, String> env, PrintStream out) {
        var exporter = new FailureCountingExporter(createExporter(env));
        SdkTracerProvider provider = setupTracing(exporter);
        // No global tracer here. Go's seeder calls otel.SetTracerProvider and
        // keeps a package-level `tracer`. We keep the tracer local and pass it
        // to emit().
        Tracer tracer = provider.get(SERVICE_NAME);

        Scenario.Config cfg = Scenario.defaultConfig();
        Integer count = parseInt(env.get("SEED_COUNT"));
        if (count != null) {
            cfg = cfg.withRequestCount(count);
        }

        // The delivery test sets this tiny: its fake receiver never
        // samples, so pacing for the Collector's adjustment_interval buys
        // nothing.
        Duration duration = parseDuration(env.get("SEED_DURATION"));
        if (duration != null) {
            cfg = cfg.withDuration(duration);
        }

        // How long after the last span is flushed to wait before shutting
        // down. The real Collector's adaptive_tail_sampling processor buffers
        // each trace for decision_delay (2s) past its root span before
        // deciding, and the adaptive_percentage sampler's goal_percentage
        // rate only settles in after adjustment_interval (15s) has seen
        // enough traces to adapt. Exit immediately after flushing and you'd
        // race the Collector's own decision window. The delivery test
        // overrides this to near-zero: its fake receiver never samples,
        // so there's nothing to wait for.
        Duration settleWait = Duration.ofSeconds(15);
        Duration settleOverride = parseDuration(env.get("SEED_SETTLE_WAIT"));
        if (settleOverride != null) {
            settleWait = settleOverride;
        }

        // Go seeds from the clock, so every run differs. new Random() does the same.
        Random rng = new Random();
        List<Scenario.Request> requests = Scenario.generate(cfg, rng);

        // Emit in chunks, flushing after each, same reasoning as session 4's
        // seeder: the BatchSpanProcessor silently drops anything that
        // overflows its queue, and this dataset emits two spans per request
        // rather than one, so the queue fills twice as fast for the same
        // request count.
        final int chunk = 500;

        // Spread requests evenly over cfg.duration() so most of them arrive
        // after the sampler's first adjustment_interval. See Scenario's class
        // comment for why a burst would demonstrate a flat rate instead.
        out.printf("emitting %d requests over %s...%n", requests.size(), GoDuration.format(cfg.duration()));
        long startNanos = System.nanoTime();
        long spacingNanos = cfg.duration().dividedBy(Math.max(requests.size(), 1)).toNanos();

        int errored = 0;
        for (int i = 0; i < requests.size(); i++) {
            Scenario.Request req = requests.get(i);
            // System.nanoTime() is a monotonic clock, like the monotonic part
            // of Go's time.Now(), so the schedule survives wall-clock changes.
            long waitNanos = startNanos + i * spacingNanos - System.nanoTime();
            if (waitNanos > 0) {
                sleep(waitNanos);
            }
            emit(tracer, req);
            if (req.errored()) {
                errored++;
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exporter, "flushing spans after " + (i + 1) + " requests");
            }
        }

        flush(provider, exporter, "final flush");

        double errorRate = (double) errored / requests.size();
        // goalPercentage matches the Collector's cost-sampling-service rule
        // (see the config referenced in the class comment). Duplicated here
        // as a literal, not imported, because this print is illustrative
        // context for the person running the seeder, not a correctness
        // dependency; the real number comes from the Collector at query time.
        final double goalPercentage = 0.05;
        double estimatedRetained = errorRate + (1 - errorRate) * goalPercentage;

        out.print(String.format(Locale.ROOT, """

            seeded %d requests (%d spans) into service "%s"
              synthetic error rate   %.1f%% (%d requests) — the Collector keeps all of these
              everything else        sampled to ~%.0f%% by the Collector's cost-sampling-service rule

            Estimated retained share once the Collector has decided: %.1f%% (~%.0f%% reduction).
            That's an estimate from this run's own numbers, not a substitute for querying
            Honeycomb — see ../session-5-cost-sampling/honeycomb-setup for the weighted-COUNT query that shows
            the real number.

            Waiting %s for the Collector's decision_delay and adjustment_interval to
            settle before exiting...
            """,
            requests.size(), requests.size() * 2, SERVICE_NAME,
            errorRate * 100, errored,
            goalPercentage * 100,
            estimatedRetained * 100, (1 - estimatedRetained) * 100,
            GoDuration.format(settleWait)));

        sleep(settleWait.toNanos());

        // Shutdown flushes anything still queued, then stops the exporter.
        CompletableResultCode shutdown = provider.shutdown().join(30, TimeUnit.SECONDS);
        if (!shutdown.isSuccess()) {
            throw new IllegalStateException("shutting down tracer provider failed");
        }
    }

    /**
     * Blocks until the exporter has shipped everything queued so far, so the
     * caller can safely generate the next chunk.
     */
    private static void flush(SdkTracerProvider provider, FailureCountingExporter exporter, String what) {
        CompletableResultCode result = provider.forceFlush().join(30, TimeUnit.SECONDS);
        // Go's ForceFlush returns the exporter's error. Java's does not: it
        // reports success once the queue is drained, even if every export
        // attempt failed (the failure is only logged). The seeder must fail
        // loudly when nothing reaches the Collector, so we count failures
        // ourselves; see FailureCountingExporter.
        if (!result.isSuccess() || exporter.failures() > 0) {
            throw new IllegalStateException(
                what + ": flush failed or timed out (is the Collector up? see ../session-1-fundamentals/collector)");
        }
    }

    /**
     * Wraps the real exporter and remembers whether any export failed. This
     * is the one place Java's SDK needs help to behave like Go's: see flush().
     */
    private static final class FailureCountingExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final AtomicInteger failures = new AtomicInteger();

        FailureCountingExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        int failures() {
            return failures.get();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            result.whenComplete(() -> {
                if (!result.isSuccess()) {
                    failures.incrementAndGet();
                }
            });
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    private static SpanExporter createExporter(Map<String, String> env) {
        // Go takes a bare host:port here (it has no scheme) and calls
        // WithInsecure(). Java's exporter wants a URL, so add http:// when
        // the value has no scheme. Plaintext in both cases: this always
        // talks to the local Collector, never straight to Honeycomb. The
        // Collector holds the API key and, for this session, the sampling
        // decision.
        String endpoint = blankToNull(env.get("OTEL_EXPORTER_OTLP_ENDPOINT"));
        if (endpoint == null) {
            endpoint = "localhost:4317";
        }
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }

        return OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
    }

    private static SdkTracerProvider setupTracing(SpanExporter exporter) {
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        return SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                .build())
            .setResource(resource)
            .build();
    }

    /**
     * Turns one generated request into a two-span trace: a root HTTP span and
     * one db.query child. Two spans, not one, because the Collector's
     * adaptive_tail_sampling processor decides per whole trace. A dataset of
     * single-span traces couldn't demonstrate that a trace is kept or dropped
     * as a unit.
     */
    static void emit(Tracer tracer, Scenario.Request req) {
        // Go: tracer.Start(ctx, ...) returns a new context holding the span,
        // and you pass that context to the child's Start. Java has the same
        // idea in io.opentelemetry.context.Context. Here we build the
        // parent link by hand with setParent(...) rather than making the
        // span "current" on the thread, so nothing leaks between requests.
        Span root = tracer.spanBuilder("POST " + req.route())
            .setNoParent()
            .startSpan();
        root.setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, HttpAttributes.HttpRequestMethodValues.POST);
        root.setAttribute(HttpAttributes.HTTP_ROUTE, req.route());
        root.setAttribute("service.environment", "production");

        Span child = tracer.spanBuilder("db.query")
            .setParent(Context.root().with(root))
            .startSpan();
        child.setAttribute("db.system", "postgresql");

        if (req.errored()) {
            for (Span span : List.of(root, child)) {
                span.setStatus(StatusCode.ERROR, req.errorType());
                span.setAttribute("error", true);
                span.setAttribute("error.type", req.errorType());
            }
        }

        child.end();
        root.end();
    }

    /** Go's strconv.Atoi followed by "ignore on error". */
    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Go's time.ParseDuration followed by "ignore on error". */
    private static Duration parseDuration(String value) {
        return value == null ? null : GoDuration.parse(value).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing emission", e);
        }
    }
}
