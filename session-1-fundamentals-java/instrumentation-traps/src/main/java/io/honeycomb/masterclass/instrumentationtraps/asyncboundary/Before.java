package io.honeycomb.masterclass.instrumentationtraps.asyncboundary;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Trap: the queue only carries the business payload. The consumer starts its
 * span from a fresh {@link Context} and creates a disconnected trace.
 */
public class Before {

    record Message(String orderId) {
    }

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-async-boundary-before")) {
            BlockingQueue<Message> queue = new ArrayBlockingQueue<>(1);

            Thread consumer = new Thread(() -> consume(queue));
            consumer.start();

            Context produceCtx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-async-boundary-before")
                    .spanBuilder("produce_order_message")
                    .startSpan());
            Span produceSpan = Span.fromContext(produceCtx);
            System.out.println("producer trace ID: " + produceSpan.getSpanContext().getTraceId());

            queue.put(new Message("ord_42"));
            produceSpan.end();

            consumer.join();
            System.out.println("BUG: the queue carried only the payload, so the consumer's span is a new trace.");
        }
    }

    private static void consume(BlockingQueue<Message> queue) {
        try {
            Message msg = queue.take();
            Context consumeCtx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-async-boundary-before")
                    .spanBuilder("consume_order_message")
                    .startSpan());
            Span span = Span.fromContext(consumeCtx);
            try {
                System.out.println("consumer trace ID:  " + span.getSpanContext().getTraceId() + " (different) order=" + msg.orderId());
                Thread.sleep(5);
            } finally {
                span.end();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
