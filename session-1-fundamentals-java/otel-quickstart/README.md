# otel-quickstart (Java)

The Masterclass 1 live demo: "auto-instrumentation → custom span," ported to
plain Java.

Go has `otelhttp`, which auto-instruments `net/http`. In Java the closest thing
without adding a web framework is the JDK's built-in `HttpServer` plus a small
SDK-based handler wrapper that extracts W3C traceparents, starts a server span,
and records the usual HTTP attributes. The conceptual demo is identical:

1. Auto-style HTTP instrumentation alone gives you `POST /api/checkout` with a
   dozen or so attributes and no application code.
2. Add `order.id` as an attribute on that request span.
3. Add a `process_order` child span where you want duration measured separately
   from the request.

## Run

```bash
../gradlew :otel-quickstart:run
```

Then:

```bash
curl -X POST http://localhost:8080/api/checkout
```

## Live demo script

1. **Show auto-instrumentation alone.** Search `OtelQuickstart.java` for
   `--- BEAT 2 ---` and `--- BEAT 3 ---` and leave both commented out. Restart
   the server, send a request, and look in Honeycomb: one span, the HTTP
   request itself, created by the wrapper.
2. **Add one attribute.** Uncomment the block inside `handleCheckout` labeled
   `BEAT 2` so `order.id` is added to the request span. Restart, send a request,
   and query by `order.id`.
3. **Add one span.** Uncomment the block inside `processOrder` labeled
   `BEAT 3` so a `process_order` span becomes a child of the request span.
   Restart and send another request.

## Notes

- The wrapper intentionally does not set `http.route` automatically;
  `setRoute()` adds it explicitly, matching the Go example's point about not
  settling for what instrumentation gives you by default.
- The server uses virtual threads (`newVirtualThreadPerTaskExecutor`), a Java
  25 feature, so the demo keeps the same small footprint as the Go version.
