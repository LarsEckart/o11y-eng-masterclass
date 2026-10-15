package io.honeycomb.masterclass.instrumentationtraps.noisylibrary;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;

/**
 * Fix: the vendor library is handed a noop tracer provider, so its internal
 * spans are never created while the application's own tracer is unaffected.
 */
public class After {

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-noisy-library-after")) {
            VendorLib client = VendorLib.newClient(TracerProvider.noop());

            Context ctx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-noisy-library-after")
                    .spanBuilder("handle_request")
                    .startSpan());
            Span span = Span.fromContext(ctx);
            try {
                client.doWork(ctx);
            } finally {
                span.end();
            }

            System.out.println("FIXED: this trace has 1 span (handle_request); vendorlib internals were never created.");
        }
    }
}
