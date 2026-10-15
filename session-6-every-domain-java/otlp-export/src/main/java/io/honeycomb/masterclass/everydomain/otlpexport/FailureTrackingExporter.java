package io.honeycomb.masterclass.everydomain.otlpexport;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a {@link SpanExporter} and counts the exports that failed.
 *
 * <p>This exists because of a real difference between the Go and Java SDKs.
 * Go's {@code TracerProvider.ForceFlush} returns the exporter's error, so a
 * seeder stops with "is the Collector up?" when nothing is listening. The Java
 * SDK's {@code forceFlush} reports success once the queue is drained, even if
 * every export failed (it only logs a SEVERE line). Without this wrapper a
 * seeder would print its cheerful success message after delivering nothing,
 * which is the very failure the delivery tests were written to prevent.</p>
 *
 * <p>Go has no equivalent because it does not need one. The three seeders
 * share this class, so it sits next to {@link OtlpExport}.</p>
 */
public final class FailureTrackingExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final AtomicInteger failures = new AtomicInteger();

    public FailureTrackingExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    /** How many exports have failed so far. */
    public int failures() {
        return failures.get();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        CompletableResultCode result = delegate.export(spans);
        // The result may complete later, on an exporter thread.
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
