package io.honeycomb.masterclass.seedsampleservice;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the sample-service seeder.
 *
 * <p>Backfills a Honeycomb dataset with historical checkout traces. Each trace
 * is built with explicit start/end timestamps so generating hundreds of traces
 * takes well under a second, and spans are flushed in chunks so the
 * BatchSpanProcessor queue does not silently drop data.</p>
 */
public class SeedSampleService {

    static final String SERVICE_NAME = "sample-service";

    // Exposed for tests that verify the queue size is large enough.
    static final int MAX_QUEUE_SIZE = 8192;

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(4);
    private static final int DEFAULT_COUNT = 400;
    private static final int CHUNK_SIZE = 400;

    public static void main(String[] args) {
        SdkTracerProvider provider = bootstrapTracing();
        Tracer tracer = GlobalOpenTelemetry.getTracer(SERVICE_NAME);

        int count = intEnv("SEED_COUNT", DEFAULT_COUNT);
        Duration window = durationEnv("SEED_WINDOW", DEFAULT_WINDOW);
        Instant now = Instant.now();
        Random rng = ThreadLocalRandom.current();

        try {
            for (int i = 0; i < count; i++) {
                long offsetMillis = (long) (rng.nextDouble() * window.toMillis());
                Instant checkoutStart = now.minusMillis(offsetMillis);
                simulateCheckout(tracer, rng, checkoutStart);

                if ((i + 1) % CHUNK_SIZE == 0) {
                    flush(provider, (i + 1));
                }
            }
            flush(provider, count);
        } finally {
            provider.shutdown();
        }

        System.out.printf("seeded %d checkout traces across the last %s into dataset \"%s\"%n", count, window, SERVICE_NAME);
    }

    private static SdkTracerProvider bootstrapTracing() {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(10))
            .build();

        SpanProcessor processor = BatchSpanProcessor.builder(exporter)
            .setMaxQueueSize(MAX_QUEUE_SIZE)
            .setMaxExportBatchSize(1024)
            .build();

        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(processor)
            .setResource(resource)
            .build();

        GlobalOpenTelemetry.set(io.opentelemetry.sdk.OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build());

        return provider;
    }

    private static void flush(SdkTracerProvider provider, int seededSoFar) {
        io.opentelemetry.sdk.common.CompletableResultCode result = provider.forceFlush();
        result.join(30, TimeUnit.SECONDS);
        if (!result.isSuccess()) {
            throw new IllegalStateException("flush timed out after " + seededSoFar + " traces; is the Collector up?");
        }
    }

    /**
     * Builds one checkout trace rooted at the given instant.
     *
     * <p>Attributes are taken from the four categories used in the slides and
     * the book's Chapter 6 tables: identity, request/execution, outcome, and
     * service/code context.</p>
     */
    static void simulateCheckout(Tracer tracer, Random rng, Instant start) {
        Span root = tracer.spanBuilder("POST /api/checkout")
            .setStartTimestamp(start)
            .startSpan();

        Instant cursor = start;
        cursor = runStep(tracer, root, cursor, "auth", jitter(rng, 5, 15), Attributes.empty());

        long inventoryMs = jitter(rng, 10, 30);
        if (rng.nextDouble() < 0.12) {
            inventoryMs = jitter(rng, 300, 800);
        }
        Attributes inventoryAttrs = Attributes.of(AttributeKeyLong("db.query.duration_ms"), inventoryMs);
        cursor = runStep(tracer, root, cursor, "inventory_check", inventoryMs, inventoryAttrs);

        boolean paymentFailed = rng.nextDouble() < 0.07;
        long paymentMs = jitter(rng, 20, 60);
        Attributes paymentAttrs = Attributes.of(AttributeKeyLong("db.query.duration_ms"), paymentMs);
        cursor = runStep(tracer, root, cursor, "payment", paymentMs, paymentAttrs);

        Attributes outcomeAttrs;
        if (paymentFailed) {
            outcomeAttrs = Attributes.builder()
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 402L)
                .put("error", true)
                .put("error.type", "payment_declined")
                .build();
            root.setStatus(StatusCode.ERROR, "payment_declined");
        } else {
            cursor = runStep(tracer, root, cursor, "confirmation", jitter(rng, 5, 10), Attributes.empty());
            outcomeAttrs = Attributes.of(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 201L);
        }

        root.setAllAttributes(Attributes.builder()
            .put(HttpAttributes.HTTP_REQUEST_METHOD, "POST")
            .put(HttpAttributes.HTTP_ROUTE, "/api/checkout")
            .put(UrlAttributes.URL_PATH, "/api/checkout")
            .put("user.id", String.format("user_%d", rng.nextInt(5000)))
            .put("user.type", weightedTier(rng))
            .put("service.version", "1.4.2")
            .put("service.environment", "production")
            .putAll(outcomeAttrs)
            .build());

        root.end(cursor);
    }

    private static Instant runStep(Tracer tracer, Span parent, Instant start, String name,
                                   long durationMillis, Attributes attrs) {
        Instant end = start.plusMillis(durationMillis);
        Span span = tracer.spanBuilder(name)
            .setParent(io.opentelemetry.context.Context.current().with(parent))
            .setStartTimestamp(start)
            .startSpan();
        span.setAllAttributes(attrs);
        span.end(end);
        return end;
    }

    private static String weightedTier(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.6) {
            return "free";
        }
        if (r < 0.9) {
            return "premium";
        }
        return "enterprise";
    }

    private static long jitter(Random rng, long min, long max) {
        return min + (long) (rng.nextDouble() * (max - min));
    }

    private static io.opentelemetry.api.common.AttributeKey<Long> AttributeKeyLong(String key) {
        return io.opentelemetry.api.common.AttributeKey.longKey(key);
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final Pattern SIMPLE_DURATION = Pattern.compile("^(\\d+)([hms])$");

    private static Duration durationEnv(String key, Duration fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Matcher m = SIMPLE_DURATION.matcher(value.trim());
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "h" -> Duration.ofHours(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "s" -> Duration.ofSeconds(amount);
                default -> fallback;
            };
        }
        try {
            return Duration.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
