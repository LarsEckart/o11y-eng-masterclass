package io.honeycomb.masterclass.everydomain.otlpexport;

import io.honeycomb.masterclass.everydomain.testsupport.FakeCollector;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpExportTest {

    /** The two ways to write {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    enum EndpointForm {
        BARE_HOST_PORT(addr -> addr),
        HTTP_URL(addr -> "http://" + addr);

        final UnaryOperator<String> format;

        EndpointForm(UnaryOperator<String> format) {
            this.format = format;
        }
    }

    @ParameterizedTest
    @EnumSource(EndpointForm.class)
    void createDeliversForEveryEndpointForm(EndpointForm form) throws Exception {
        try (var receiver = FakeCollector.start()) {
            SpanExporter exporter = OtlpExport.create(form.format.apply(receiver.address()));
            var provider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();

            provider.get("test").spanBuilder("probe").startSpan().end();

            // Shutdown flushes the batch processor, so this is the delivery.
            var shutdown = provider.shutdown().join(10, TimeUnit.SECONDS);
            assertThat(shutdown.isSuccess()).as("flushing spans").isTrue();

            assertThat(receiver.collected()).as("spans the receiver got").hasSize(1);
        }
    }

    @Test
    void createDefaultsToLocalCollector() {
        // Nothing listens on localhost:4317 in the test, and nothing needs to:
        // the exporter connects lazily, on the first export. Go's test checks
        // the same two things, that an empty endpoint is accepted and that the
        // exporter shuts down cleanly. Null is the Java spelling of "unset".
        for (String unset : new String[] {"", null}) {
            SpanExporter exporter = OtlpExport.create(unset);
            assertThat(exporter.shutdown().join(10, TimeUnit.SECONDS).isSuccess()).as("shutdown").isTrue();
        }
    }
}
