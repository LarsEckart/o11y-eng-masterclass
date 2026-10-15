package io.honeycomb.masterclass.feedbackloops.seedcanaryregression;

import io.honeycomb.masterclass.feedbackloops.scenario.Config;
import io.honeycomb.masterclass.feedbackloops.scenario.Request;
import io.honeycomb.masterclass.feedbackloops.scenario.Scenario;
import io.honeycomb.masterclass.feedbackloops.scenario.Step;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Backfills the Masterclass 2 dataset: a canary deploy that is ~40% slower, but
 * only for enterprise users on the billing endpoint. See {@link Scenario} for
 * the scenario and why its proportions are what they are.
 *
 * <p>Run this before the session, not during it. It writes into the same
 * sample-service dataset Masterclass 1 uses, so the deploy marker and the
 * before/after queries land on data attendees already recognise.</p>
 *
 * <p>On completion it prints the exact deploy timestamp, which the Honeycomb
 * deploy marker must match. See
 * {@code ../session-2-feedback-loops/honeycomb-setup}.</p>
 */
public final class SeedCanaryRegression {

    static final String SERVICE_NAME = "sample-service";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it: a test that seeds fewer spans than this cannot
     * detect a dropped-span regression, so it asserts against this value rather
     * than a hardcoded guess that would silently rot if this changed.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    private SeedCanaryRegression() {
    }

    public static void main(String[] args) throws Exception {
        var exports = new FailureTrackingExporter(createExporter());
        SdkTracerProvider provider = setupTracing(exports);
        Tracer tracer = provider.get(SERVICE_NAME);

        // Config is immutable, so each SEED_* override produces a new Config.
        // A value that does not parse is ignored and the default stays, as in Go.
        Config cfg = Config.defaults(Instant.now());
        var traceCount = parseInt(System.getenv("SEED_COUNT"));
        if (traceCount != null) {
            cfg = cfg.withTraceCount(traceCount);
        }
        var window = GoDuration.parse(System.getenv("SEED_WINDOW"));
        if (window.isPresent()) {
            cfg = cfg.withWindow(window.get());
        }
        var deployAgo = GoDuration.parse(System.getenv("SEED_DEPLOY_AGO"));
        if (deployAgo.isPresent()) {
            cfg = cfg.withDeployAgo(deployAgo.get());
        }

        // Go seeds from the clock; new Random() does the same.
        var rng = new Random();
        var requests = Scenario.generate(cfg, rng);

        // Emit in chunks, flushing after each. A backfill generates spans far
        // faster than the exporter ships them, and the BatchSpanProcessor
        // silently drops anything that overflows its queue, so without this,
        // seeding 10,000 requests lands roughly 500 of them in Honeycomb and
        // reports success. Chunking bounds the queue depth instead of trusting
        // it.
        final int chunk = 400;

        int regressed = 0;
        for (int i = 0; i < requests.size(); i++) {
            var req = requests.get(i);
            emit(tracer, req);
            if (req.regressed()) {
                regressed++;
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exports, "flushing spans after " + (i + 1) + " requests");
            }
        }

        flush(provider, exports, "final flush");
        var shutdown = provider.shutdown().join(30, TimeUnit.SECONDS);
        if (!shutdown.isSuccess()) {
            fatal("shutting down tracer provider failed");
        }

        Instant deploy = cfg.deployTime();
        // Go prints the deploy time in the machine's local zone, without
        // fractional seconds (RFC 3339). Same here.
        String deployRfc3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(deploy.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));

        // The paths below are relative to the session-2-feedback-loops-java
        // directory (Go prints them relative to cmd/seed-canary-regression).
        System.out.print("""

                seeded %d requests into dataset "%s" over the last %s
                  canary build     %s  (%d requests regressed, %.2f%% of traffic)
                  baseline build   %s
                  deploy time      %s

                Next: create the deploy marker at that exact time, or the before/after
                boundary will not line up with the data.

                  cd ../session-2-feedback-loops/honeycomb-setup/scripts
                  HONEYCOMB_API_KEY=<config-permission key> ./create-deploy-marker.sh %d

                """.formatted(
                requests.size(), SERVICE_NAME, GoDuration.format(cfg.window()),
                cfg.canaryVersion(), regressed, 100.0 * regressed / requests.size(),
                cfg.baselineVersion(),
                deployRfc3339,
                deploy.getEpochSecond()));
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

    private static SpanExporter createExporter() {
        String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "localhost:4317";
        }
        // Go takes a bare host:port and sends plaintext (WithInsecure). The Java
        // exporter wants a URL, where the scheme picks plaintext or TLS. A bare
        // host:port gets "http://" so both spellings of the variable work.
        // Plaintext is right here because this always talks to the local
        // Collector, never straight to Honeycomb; the Collector holds the API key.
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }

        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }

    private static SdkTracerProvider setupTracing(SpanExporter exporter) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        return SdkTracerProvider.builder()
                // A queue well above the 2048 default, so a chunk never comes
                // close to overflowing it even if an export is briefly slow.
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(1024)
                        .build())
                .setResource(resource)
                .build();
    }

    /**
     * Turns one generated request into a root span plus its child spans, at
     * explicit historical timestamps. Attribute names match Masterclass 1's
     * dataset: the book's Chapter 6 tables where the book has an opinion,
     * current stable OTel semantic conventions otherwise.
     */
    static void emit(Tracer tracer, Request req) {
        String route = req.route().path();

        Span root = tracer.spanBuilder("POST " + route)
                .setStartTimestamp(req.start())
                .startSpan();

        AttributesBuilder attrs = Attributes.builder()
                .put(HttpAttributes.HTTP_REQUEST_METHOD, "POST")
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
            root.setStatus(StatusCode.ERROR, req.errorType());
        }
        root.setAllAttributes(attrs.build());

        // Go gets a new ctx back from tracer.Start and passes it to the child
        // spans. Java has no ctx parameter: the "current" span lives in a
        // thread-local Context. We never make the root current, so we name the
        // parent explicitly instead.
        Context rootContext = Context.root().with(root);

        Instant cursor = req.start();
        for (Step step : req.steps()) {
            Instant end = cursor.plus(step.duration());
            // No duration attribute here on purpose. A span's duration is
            // already computed from its start and end timestamps, and Honeycomb
            // surfaces it as duration_ms, so emitting an attribute by that name
            // shadows the real field in the one demo that is entirely about
            // reading durations. Masterclass 1 writes into this same dataset
            // and uses db.query.duration_ms for a genuinely distinct
            // measurement; a second spelling of "how long did this take" is
            // exactly the ontology drift Chapter 7 warns about.
            Span span = tracer.spanBuilder(step.name())
                    .setParent(rootContext)
                    .setStartTimestamp(cursor)
                    .startSpan();
            span.end(end);
            cursor = end;
        }

        root.end(cursor);
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
