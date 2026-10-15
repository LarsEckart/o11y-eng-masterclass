package io.honeycomb.masterclass.seedsampleservice;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies trace invariants that the live demo relies on.
 */
class SeedSampleServiceTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private io.opentelemetry.api.trace.Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        GlobalOpenTelemetry.set(io.opentelemetry.sdk.OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build());
        tracer = GlobalOpenTelemetry.getTracer(SeedSampleService.SERVICE_NAME);
    }

    @AfterEach
    void tearDown() {
        provider.shutdown();
        exporter.reset();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void oneRootPerCheckoutWithMatchingChildren() {
        Random rng = new Random(42);
        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            SeedSampleService.simulateCheckout(tracer, rng, Instant.now());
        }
        provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        Map<String, List<SpanData>> byTrace = spans.stream()
            .collect(Collectors.groupingBy(SpanData::getTraceId));

        assertThat(byTrace).hasSize(iterations);

        boolean sawSuccess = false;
        boolean sawFailure = false;
        boolean sawSlowInventory = false;

        for (List<SpanData> trace : byTrace.values()) {
            List<SpanData> roots = trace.stream().filter(s -> !s.getParentSpanContext().isValid()).toList();
            assertThat(roots).hasSize(1);
            SpanData root = roots.get(0);

            List<SpanData> children = trace.stream().filter(s -> s.getParentSpanContext().getSpanId().equals(root.getSpanId())).toList();
            Long status = longAttr(root, "http.response.status_code");
            assertThat(status).isNotNull();

            if (status == 402) {
                sawFailure = true;
                assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(children).hasSize(3);
            } else {
                sawSuccess = true;
                assertThat(root.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
                assertThat(children).hasSize(4);
            }

            for (SpanData child : children) {
                assertThat(child.getEndEpochNanos()).isGreaterThanOrEqualTo(child.getStartEpochNanos());
                if ("inventory_check".equals(child.getName())) {
                    Long dbMs = longAttr(child, "db.query.duration_ms");
                    if (dbMs != null && dbMs > 200) {
                        sawSlowInventory = true;
                    }
                }
            }
        }

        assertThat(sawSuccess).isTrue();
        assertThat(sawFailure).isTrue();
        assertThat(sawSlowInventory).isTrue();
    }

    private static Long longAttr(SpanData span, String key) {
        Object value = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey(key));
        return value instanceof Number n ? n.longValue() : null;
    }
}
