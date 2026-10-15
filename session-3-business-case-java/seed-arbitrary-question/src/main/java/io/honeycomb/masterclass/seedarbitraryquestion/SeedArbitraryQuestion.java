package io.honeycomb.masterclass.seedarbitraryquestion;

import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Config;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Event;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.EventAttributes;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Generator;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backfills the Masterclass 3 dataset: a week of genuinely wide checkout
 * events, used to run Chapter 28's Arbitrary Question Test live. This is the
 * Java port of Go's {@code cmd/seed-arbitrary-question}.
 *
 * <p>Run this before the session. It writes to its own dataset rather than the
 * sample-service one Masterclass 1 and 2 use, so rehearsing all three sessions
 * in a row does not put a week of differently-shaped data inside their 4-hour
 * windows.
 *
 * <p>On completion it prints the question to ask and how many events actually
 * answer it, so you know before going live that the result will not be empty.
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT}: Collector address, default
 *       {@code localhost:4317}. Both {@code host:port} and a full
 *       {@code http://host:port} URL work.
 *   <li>{@code OTEL_EXPORTER_OTLP_PROTOCOL}: {@code grpc} (default) or
 *       {@code http/protobuf}. Java-only addition, see {@link #createExporter}.
 *   <li>{@code SEED_COUNT}: number of events, default 20000.
 *   <li>{@code SEED_WINDOW}: how far back to spread them, in Go duration syntax
 *       such as {@code 168h} (the default) or {@code 72h30m}.
 * </ul>
 */
public final class SeedArbitraryQuestion {

    static final String SERVICE_NAME = "checkout-web";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test asserts against it: a test seeding fewer spans than this
     * cannot detect a dropped-span regression at all.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    private SeedArbitraryQuestion() {}

    public static void main(String[] args) {
        try {
            run();
        } catch (IllegalStateException e) {
            // Go's log.Fatalf: message on stderr, non-zero exit.
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run() {
        FailureTrackingExporter exporter = new FailureTrackingExporter(createExporter(
                System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
                System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL")));
        SdkTracerProvider provider = setupTracing(exporter);
        // Go keeps a package-level tracer from the global provider. Here the
        // provider is passed around explicitly instead; nothing is registered
        // as a global because the seeder has no other code that would look it up.
        Tracer tracer = provider.get(SERVICE_NAME);

        Config cfg = Config.defaultConfig(ZonedDateTime.now());
        String count = System.getenv("SEED_COUNT");
        if (count != null && !count.isEmpty()) {
            try {
                cfg = cfg.withEventCount(Integer.parseInt(count));
            } catch (NumberFormatException ignored) {
                // Same as Go: an unparseable value silently keeps the default.
            }
        }
        String window = System.getenv("SEED_WINDOW");
        if (window != null && !window.isEmpty()) {
            try {
                cfg = cfg.withWindow(GoDuration.parse(window));
            } catch (IllegalArgumentException | ArithmeticException ignored) {
                // Same as Go: an unparseable value silently keeps the default.
            }
        }

        Random rng = new Random();
        List<Event> events = Generator.generate(cfg, rng);

        // Chunked flushing, for the same reason as the other seeders: the
        // BatchSpanProcessor silently drops whatever overflows its queue, so a
        // single trailing flush loses most of a backfill and still exits zero.
        final int chunk = 500;

        int matches = 0;
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            emit(tracer, e);
            if (e.matchesArbitraryQuestion()) {
                matches++;
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exporter, "flushing spans after " + (i + 1) + " events");
            }
        }

        flush(provider, exporter, "final flush");
        CompletableResultCode shutdown = provider.shutdown().join(30, TimeUnit.SECONDS);
        if (!shutdown.isSuccess()) {
            throw new IllegalStateException("shutting down tracer provider failed");
        }

        System.out.print(String.format(Locale.ROOT, """

                seeded %d checkout events into dataset "%s" over the last %s
                  attributes per event   %d
                  matching the question  %d

                The Arbitrary Question Test (Chapter 28). Ask:

                  "Show me all failed checkout attempts from mobile users in California
                   using version %s of the app during lunch hour over the past week."

                In Honeycomb, that is a COUNT with these filters:

                  error                  = true
                  user_agent.device      = %s          <- "mobile" is device=phone in the convention
                  geo.region.iso_code    = %s          <- California, ISO 3166-2
                  user_agent.app_version = %s
                  and a %d:00-%d:00 time-of-day filter

                Expect roughly %d results. If it comes back empty, reseed before going live.

                """,
                events.size(), SERVICE_NAME, GoDuration.format(cfg.window()),
                EventAttributes.of(events.get(0)).size(),
                matches,
                Event.ANSWER_VERSION,
                Event.ANSWER_DEVICE,
                Event.ANSWER_REGION,
                Event.ANSWER_VERSION,
                Event.LUNCH_HOUR_START, Event.LUNCH_HOUR_END,
                matches));
    }

    /**
     * Flushes and fails loudly if the data did not leave the process.
     *
     * <p>Go's {@code ForceFlush} returns the exporter's error, so an unreachable
     * Collector stops the seeder. Java's {@code forceFlush()} reports success as
     * long as the flush finished, even when every export failed, so the
     * {@link FailureTrackingExporter} counts the failures for us.
     */
    private static void flush(SdkTracerProvider provider, FailureTrackingExporter exporter, String what) {
        CompletableResultCode result = provider.forceFlush().join(30, TimeUnit.SECONDS);
        if (!result.isSuccess() || exporter.failures() > 0) {
            throw new IllegalStateException(what
                    + ": export failed (is the Collector up? see ../session-1-fundamentals/collector)");
        }
    }

    /**
     * Builds the OTLP exporter for the given endpoint and protocol (either may
     * be null).
     *
     * <p>The Go seeder always speaks gRPC, and so does this one by default.
     * {@code OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf} switches to OTLP over
     * HTTP. It is a standard OpenTelemetry variable, and it exists here so the
     * delivery test can receive spans with a plain JDK {@code HttpServer}
     * (gRPC would need an HTTP/2 server and generated stubs).
     */
    static SpanExporter createExporter(String endpoint, String protocol) {
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "localhost:4317";
        }
        // Go's WithEndpoint takes a bare host:port and WithInsecure() turns TLS
        // off. Java takes a URL, where "http://" means plaintext. This always
        // talks to the local Collector over plaintext, never straight to
        // Honeycomb; the Collector holds the API key.
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }

        if (protocol != null && protocol.equals("http/protobuf")) {
            return OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint.replaceAll("/+$", "") + "/v1/traces")
                    .build();
        }
        return OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
    }

    static SdkTracerProvider setupTracing(SpanExporter exporter) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        return SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(1024)
                        .build())
                .setResource(resource)
                .build();
    }

    /**
     * Writes one wide event as a single span. There are no child spans here on
     * purpose: this dataset exists to demonstrate querying across many
     * attributes, which is the canonical-log shape rather than the trace shape.
     *
     * <p>Go passes a {@code context.Context} to {@code tracer.Start}; a
     * background context means "no parent". Java's span builder reads the
     * current context from a thread-local instead, so {@code setNoParent()}
     * says the same thing explicitly.
     */
    static void emit(Tracer tracer, Event e) {
        Span span = tracer.spanBuilder("POST " + e.route())
                .setNoParent()
                .setStartTimestamp(e.start().toInstant())
                .startSpan();
        span.setAllAttributes(EventAttributes.of(e));
        if (e.errored()) {
            span.setStatus(StatusCode.ERROR, e.errorType());
        }
        span.end(e.start().plus(e.duration()).toInstant());
    }

    /** Wraps an exporter and counts failed exports. See {@link #flush}. */
    private static final class FailureTrackingExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final AtomicInteger failures = new AtomicInteger();

        FailureTrackingExporter(SpanExporter delegate) {
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
}
