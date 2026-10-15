package io.honeycomb.masterclass.slisslos.seedsliservice;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a {@link SpanExporter} and counts failed exports.
 *
 * <p>This exists because of a real difference between the SDKs. In Go,
 * {@code provider.ForceFlush(ctx)} returns the exporter's error, so a seeder
 * with no Collector behind it fails loudly. In Java,
 * {@code SdkTracerProvider.forceFlush()} reports success once the batch has been
 * <em>handed</em> to the exporter; if the export itself fails, the SDK only
 * logs a warning. Without this wrapper the seeder would print "seeded N
 * requests" while delivering nothing, which is the exact failure the delivery
 * test is meant to rule out.
 */
final class FailureTrackingExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final AtomicInteger failures = new AtomicInteger();

    FailureTrackingExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    /** How many export calls have failed so far. */
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
