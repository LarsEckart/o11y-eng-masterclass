package io.honeycomb.masterclass.everydomain.seedperfservice;

import io.honeycomb.masterclass.everydomain.otlpexport.FailureTrackingExporter;
import io.honeycomb.masterclass.everydomain.otlpexport.OtlpExport;
import io.honeycomb.masterclass.everydomain.perfscenario.Config;
import io.honeycomb.masterclass.everydomain.perfscenario.Request;
import io.honeycomb.masterclass.everydomain.perfscenario.Scenario;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Backfills the Masterclass 6 performance-engineering dataset: five
 * parameterized {@code db.query.text} templates (three fast, one bimodal, one
 * long-tail), a {@code user.type} correlation, and a Graviton (amd64 to arm64)
 * migration marker halfway through the window with no latency regression across
 * it. See {@link Scenario} for the scenario and why its proportions are what
 * they are.
 *
 * <p>Run this against the Collector from {@code session-1-fundamentals/collector},
 * same as every other session.</p>
 */
public final class SeedPerfService {

    static final String SERVICE_NAME = "perf-service";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it, same rationale as every other seeder in this repo.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    /**
     * Spans sent per export call. Also named for the delivery test: one batch
     * can be in flight while the queue behind it is full, so the queue plus one
     * batch is the most the SDK can hold before it starts dropping.
     */
    static final int MAX_EXPORT_BATCH_SIZE = 1024;

    private SeedPerfService() {
    }

    public static void main(String[] args) {
        var exports = new FailureTrackingExporter(OtlpExport.create(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")));
        SdkTracerProvider provider = setupTracing(exports);
        // Go keeps the tracer in a package variable. Java passes it along
        // explicitly, which keeps emit easy to call from a test.
        Tracer tracer = provider.get(SERVICE_NAME);

        Config cfg = Config.defaults(Instant.now());
        Integer count = parseInt(System.getenv("SEED_COUNT"));
        if (count != null) {
            cfg = cfg.withRequestCount(count);
        }

        // Go seeds from the clock; new Random() does the same.
        var rng = new Random();
        List<Request> requests = Scenario.generate(cfg, rng);

        final int chunk = 1000;

        var amd64Reqs = new ArrayList<Request>();
        var arm64Reqs = new ArrayList<Request>();
        for (int i = 0; i < requests.size(); i++) {
            Request req = requests.get(i);
            emit(tracer, req);
            if (req.arch().equals("amd64")) {
                amd64Reqs.add(req);
            } else {
                arm64Reqs.add(req);
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exports, "flushing spans after " + (i + 1) + " requests");
            }
        }

        flush(provider, exports, "final flush");
        if (!provider.shutdown().join(30, TimeUnit.SECONDS).isSuccess()) {
            fatal("shutting down tracer provider failed");
        }

        // Go prints the time in the machine's local zone, as RFC 3339 without
        // fractional seconds. Same here.
        String migrationStart = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(cfg.migrationStart().truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));

        System.out.print("""

                seeded %d requests into service "%s"
                  migration          completed %s (amd64 before, arm64 after)
                  amd64 requests     %d   P50 %s   P95 %s
                  arm64 requests     %d   P50 %s   P95 %s

                Compare the P50/P95 pairs above: that's the "no latency regression" claim
                this seeder is built to make honestly. See ../session-6-every-domain/honeycomb-setup for the
                saved queries that reproduce this comparison in Honeycomb itself.
                """.formatted(
                requests.size(), SERVICE_NAME,
                migrationStart,
                amd64Reqs.size(), GoDuration.format(Scenario.percentile(amd64Reqs, 50)), GoDuration.format(Scenario.percentile(amd64Reqs, 95)),
                arm64Reqs.size(), GoDuration.format(Scenario.percentile(arm64Reqs, 50)), GoDuration.format(Scenario.percentile(arm64Reqs, 95))));
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
     * Turns one generated request into a single root span at an explicit
     * historical timestamp. This dataset is about query duration analysis across
     * a large population, not a trace waterfall, so there is nothing a child
     * span would add. Same reasoning as session 4's single-span seeder.
     */
    static void emit(Tracer tracer, Request req) {
        Span span = tracer.spanBuilder("db.query")
                .setStartTimestamp(req.start())
                .startSpan();
        // The Go version uses semconv.HostArchKey. host.arch is not yet in the
        // stable Java semconv artifact (it lives in the incubating one), so
        // the name is a plain string here. The delivery test checks it on the
        // wire.
        span.setAttribute("db.query.text", req.queryText());
        span.setAttribute("user.type", req.userType());
        span.setAttribute("host.arch", req.arch());
        span.end(req.start().plus(req.duration()));
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
