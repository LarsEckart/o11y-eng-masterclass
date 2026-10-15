package io.honeycomb.masterclass.instrumentationtraps.noisylibrary;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * Trap: the vendor library is given the real global tracer provider, so its
 * internal spans flood every trace.
 */
public class Before {

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-noisy-library-before")) {
            VendorLib client = VendorLib.newClient(GlobalOpenTelemetry.getTracerProvider());

            Context ctx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-noisy-library-before")
                    .spanBuilder("handle_request")
                    .startSpan());
            Span span = Span.fromContext(ctx);
            try {
                client.doWork(ctx);
            } finally {
                span.end();
            }

            System.out.println("BUG: this trace has 5 spans (handle_request + 4 noisy vendorlib internals).");
        }
    }
}
