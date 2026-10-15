package io.honeycomb.masterclass.feedbackloops.seedcanaryregression;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a {@link SpanExporter} and counts the exports that failed.
 *
 * <p>This exists because of a real difference between the Go and Java SDKs.
 * Go's {@code TracerProvider.ForceFlush} returns the exporter's error, so the
 * seeder stops with "is the Collector up?" when nothing is listening. The Java
 * SDK's {@code forceFlush} reports success once the queue is drained, even if
 * every export failed (it only logs a SEVERE line). Without this wrapper the
 * seeder would print its cheerful success message after delivering nothing,
 * which is the very failure the delivery test was written to prevent.</p>
 */
final class FailureTrackingExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final AtomicInteger failures = new AtomicInteger();

    FailureTrackingExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    /** How many exports have failed so far. */
    int failures() {
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
