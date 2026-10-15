package io.honeycomb.masterclass.instrumentationtraps.noisylibrary;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;

import java.util.List;

/**
 * Stand-in for a chatty third-party dependency. The only thing that changes
 * between the before/after demos is the {@link TracerProvider} passed to
 * {@link #newClient(TracerProvider)}.
 */
public final class VendorLib {

    private final Tracer tracer;

    private VendorLib(TracerProvider provider) {
        this.tracer = provider.get("vendor-library-x");
    }

    public static VendorLib newClient(TracerProvider provider) {
        return new VendorLib(provider);
    }

    public void doWork(Context parent) {
        for (String step : List.of("acquire_connection", "serialize", "network_write", "network_read")) {
            Span span = tracer.spanBuilder(step).setParent(parent).startSpan();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                span.end();
            }
        }
    }
}
