package io.honeycomb.masterclass.slisslos.seedsliservice;

import io.honeycomb.masterclass.slisslos.scenario.Config;
import io.honeycomb.masterclass.slisslos.scenario.Generator;
import io.honeycomb.masterclass.slisslos.scenario.Request;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Backfills the Masterclass 4 dataset: a service that is mostly healthy, with
 * an ongoing incident affecting enterprise customers on an older build. See
 * the {@code scenario} module for the scenario and why its proportions are what
 * they are.
 *
 * <p>Run this before the session, not during it. Because the incident is
 * deliberately still "ongoing" relative to the seeded data's {@code now},
 * re-run this shortly before you go live so the trailing-1-hour burn query and
 * trigger have something current to find. A seed from yesterday reads as
 * history, not an active incident.
 *
 * <p>This dataset backs the free-tier workshop path: a
 * {@code honeycombio_derived_column} implementing the SLI plus an AVG() query
 * and a {@code honeycombio_trigger}, rather than a native SLO/burn-alert object
 * (Honeycomb Free does not include SLOs). See
 * {@code ../session-4-slis-slos/honeycomb-setup} and
 * {@code ../session-4-slis-slos/README.md}.
 */
public final class SeedSliService {

    static final String SERVICE_NAME = "sli-demo-service";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it: a test that seeds fewer spans than this cannot
     * detect a dropped-span regression, so it asserts against this value rather
     * than a hardcoded guess that would silently rot if this changed.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    /**
     * Spans per export call. The batch being exported is already out of the
     * queue, so the queue plus one batch is the most the processor can hold
     * before it drops spans. The delivery test needs this number too.
     */
    static final int MAX_EXPORT_BATCH_SIZE = 1024;

    private SeedSliService() {
    }

    public static void main(String[] args) {
        try {
            run(System.getenv(), System.out);
        } catch (RuntimeException e) {
            // The Go version calls log.Fatalf: print the reason, exit non-zero.
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * The whole program, with its inputs passed in. The delivery test runs
     * {@code main} in its own JVM, like Go's {@code go run .}, so nothing else
     * calls this. The parameters just keep the environment and output visible
     * at the top instead of buried in the body.
     */
    private static void run(Map<String, String> env, PrintStream out) {
        Tracing tracing = setupTracing(env);
        SdkTracerProvider provider = tracing.provider();
        // Go registers the provider globally (otel.SetTracerProvider) and reads
        // it from a package variable. Here we hold the tracer directly: the
        // global can be set only once per JVM, which would break running the
        // seeder repeatedly from tests.
        Tracer tracer = provider.get(SERVICE_NAME);

        Config cfg = Config.defaults(Instant.now());
        Integer count = parseInt(env.get("SEED_COUNT"));
        if (count != null) {
            cfg = cfg.withRequestCount(count);
        }
        Duration window = parseDuration(env.get("SEED_WINDOW"));
        if (window != null) {
            cfg = cfg.withWindow(window);
        }
        Duration incidentAgo = parseDuration(env.get("SEED_INCIDENT_AGO"));
        if (incidentAgo != null) {
            cfg = cfg.withIncidentAgo(incidentAgo);
        }

        // new Random() seeds itself differently on every run, like the Go
        // version's time-based seed.
        List<Request> requests = Generator.generate(cfg, new Random());

        // Emit in chunks, flushing after each. A backfill generates spans far
        // faster than the exporter ships them, and the BatchSpanProcessor
        // silently drops anything that overflows its queue, so without this,
        // seeding a large batch lands only a fraction of it in Honeycomb and
        // reports success. Chunking bounds the queue depth instead of trusting
        // it.
        final int chunk = 1000;

        int incidentPop = 0;
        int incidentErrors = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request req = requests.get(i);
            emit(tracer, req);
            if (req.incident()) {
                incidentPop++;
                if (req.errored()) {
                    incidentErrors++;
                }
            }
            if ((i + 1) % chunk == 0) {
                flush(tracing, "flushing spans after " + (i + 1) + " requests");
            }
        }

        flush(tracing, "final flush");
        CompletableResultCode shutdown = provider.shutdown().join(30, TimeUnit.SECONDS);
        if (!shutdown.isSuccess()) {
            throw new IllegalStateException("shutting down tracer provider failed");
        }

        Instant incidentStart = cfg.incidentStart();
        // Go prints RFC 3339 in the local zone, e.g. 2026-09-16T14:00:00+02:00.
        String incidentStartText = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(incidentStart.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));
        out.printf("""

                seeded %d requests into dataset "%s" over the last %s
                  incident window   started %s, still ongoing at seed time
                  affected build     %s   (healthy build: %s)
                  incident population %d requests (%d errored, %.0f%% observed rate)

                The incident is anchored relative to now, so the trailing-1-hour SLI query and
                the trigger in ../session-4-slis-slos/honeycomb-setup only find it if you seed
                shortly before you go live; a seed from yesterday reads as history, not a
                current burn.

                Next: apply the Honeycomb Terraform (derived column, SLI query, trigger,
                marker) so the workshop path has something to query and something to fire.

                  cd ../session-4-slis-slos/honeycomb-setup/terraform
                  terraform apply

                """,
                requests.size(), SERVICE_NAME, GoDuration.format(cfg.window()),
                incidentStartText,
                cfg.buggyVersion(), cfg.healthyVersion(),
                incidentPop, incidentErrors, 100 * (double) incidentErrors / incidentPop);
    }

    /**
     * Blocks until the exporter has shipped everything queued so far, so the
     * caller can safely generate the next chunk.
     */
    private static void flush(Tracing tracing, String what) {
        CompletableResultCode result = tracing.provider().forceFlush().join(30, TimeUnit.SECONDS);
        // Go's ForceFlush returns the export error itself. Java's does not, so
        // we also ask the wrapping exporter whether anything failed.
        if (!result.isSuccess() || tracing.exporter().failures() > 0) {
            throw new IllegalStateException(what + " failed"
                    + " (is the Collector up? see ../session-1-fundamentals/collector)");
        }
    }

    /** The provider, plus the exporter wrapper that tells us whether exports worked. */
    private record Tracing(SdkTracerProvider provider, FailureTrackingExporter exporter) {
    }

    private static Tracing setupTracing(Map<String, String> env) {
        String endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "localhost:4317";
        }
        // Go's exporter accepts a bare "host:port". Java's wants a full URL.
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }

        FailureTrackingExporter exporter =
                new FailureTrackingExporter(createExporter(endpoint));

        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        // A queue well above the 2048 default, so a chunk never comes close to
        // overflowing it even if an export is briefly slow.
        SpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                .build();

        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .setResource(resource)
                .build();
        return new Tracing(provider, exporter);
    }

    /**
     * Always plaintext, because this talks to the local Collector, never
     * straight to Honeycomb; the Collector holds the API key. (Go says
     * {@code WithInsecure()}; in Java an {@code http://} URL means the same.)
     * gRPC only, to port 4317 by default, exactly like the Go version.
     */
    private static SpanExporter createExporter(String endpoint) {
        return OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
    }

    /**
     * Turns one generated request into a single root span at an explicit
     * historical timestamp. Every request here is a standalone event rather
     * than a multi-step trace: this dataset is about the SLI ratio over the
     * whole population, not about drilling into one slow trace's waterfall, so
     * there is nothing a child span would add.
     */
    static void emit(Tracer tracer, Request req) {
        // semconv v1.26 (Go) and v1.32 (here) both define these as plain
        // string and int attributes; the names and values are what matter.
        String method = req.method();
        String route = req.route().path();

        // setNoParent: Go passes context.Background(), so there is no parent.
        // Java would otherwise look at the thread's current context.
        Span span = tracer.spanBuilder(method + " " + route)
                .setNoParent()
                .setStartTimestamp(req.start())
                .startSpan();

        AttributesBuilder attrs = Attributes.builder()
                .put(HttpAttributes.HTTP_REQUEST_METHOD, method)
                .put(HttpAttributes.HTTP_ROUTE, route)
                .put(UrlAttributes.URL_PATH, route)
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long) req.statusCode())
                .put("user.id", "user_" + req.userId())
                .put("user.type", req.userType())
                .put("service.version", req.version())
                .put("service.environment", "production");
        if (req.errored()) {
            attrs.put(AttributeKey.booleanKey("error"), true)
                    .put("error.type", req.errorType());
            span.setStatus(StatusCode.ERROR, req.errorType());
        }
        span.setAllAttributes(attrs.build());

        span.end(req.start().plus(req.duration()));
    }

    /** Like Go's {@code strconv.Atoi} with the error ignored: bad input means "keep the default". */
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

    /** Like Go's {@code time.ParseDuration} with the error ignored: bad input means "keep the default". */
    private static Duration parseDuration(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return GoDuration.parse(value).orElse(null);
    }
}
