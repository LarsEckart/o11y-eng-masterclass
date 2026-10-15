package io.honeycomb.masterclass.instrumentationtraps.shared;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;

/**
 * Shared bootstrap used by all the instrumentation-trap demos so each
 * before/after pair can focus on the bug, not the SDK wiring.
 */
public final class DemoTrace {

    private DemoTrace() {
        // utility class
    }

    public static AutoCloseable setup(String serviceName) {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(10))
            .build();

        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, serviceName)));

        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(resource)
            .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();

        return () -> {
            // sdk.shutdown() flushes and shuts down the provider. Wait so the
            // spans actually leave the process before the JVM exits. Ignore
            // failures so the demo still prints its result when the Collector
            // is not running; the point is to see the trace IDs.
            try {
                sdk.shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                System.err.println("warning: shutdown failed (is the Collector up?): " + e.getMessage());
            }
        };
    }
}
