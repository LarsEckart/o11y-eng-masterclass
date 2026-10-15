package io.honeycomb.masterclass.seedarbitraryquestion;

import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Config;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Event;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Generator;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Go seeder has no unit test for {@code emit}; the delivery test covers it
 * from the outside. This one checks the span shape in-process with
 * {@code InMemorySpanExporter}, the Java counterpart of Go's {@code tracetest}.
 */
class EmitTest {

    @Test
    void oneRootSpanPerEventWithTheEventsTimestampsAndStatus() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        Config cfg = new Config(300, Duration.ofDays(7), ZonedDateTime.of(2026, 9, 2, 15, 0, 0, 0, ZoneOffset.UTC));
        List<Event> events = Generator.generate(cfg, new Random(3));

        for (Event e : events) {
            SeedArbitraryQuestion.emit(provider.get(SeedArbitraryQuestion.SERVICE_NAME), e);
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(events.size());

        boolean sawError = false;
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            SpanData span = spans.get(i);

            assertThat(span.getName()).isEqualTo("POST /api/checkout");
            assertThat(span.getParentSpanContext().isValid()).as("no child spans, no parents").isFalse();
            assertThat(span.getStartEpochNanos())
                    .isEqualTo(e.start().toEpochSecond() * 1_000_000_000L + e.start().getNano());
            assertThat(span.getEndEpochNanos() - span.getStartEpochNanos()).isEqualTo(e.duration().toNanos());

            if (e.errored()) {
                sawError = true;
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(span.getStatus().getDescription()).isEqualTo(e.errorType());
            } else {
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            }
        }
        assertThat(sawError).isTrue();

        provider.shutdown();
    }
}
