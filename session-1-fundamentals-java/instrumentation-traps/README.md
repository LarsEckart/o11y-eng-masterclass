# instrumentation-traps (Java)

Async-lab material: the three instrumentation traps from the slides, each as a
before/after pair.

| Trap | Before class | After class | The bug |
|------|--------------|-------------|---------|
| Missing context propagation | `contextpropagation.Before` | `contextpropagation.After` | A helper takes no `Context`, so its span starts a new trace. |
| Async boundaries drop context | `asyncboundary.Before` | `asyncboundary.After` | A queue carries only the payload, so the consumer has no trace context. |
| Noisy library instrumentation | `noisylibrary.Before` | `noisylibrary.After` | A dependency's own spans flood every trace; fixed with `TracerProvider.noop()`. |

## Run one

```bash
../gradlew :instrumentation-traps:run \
    -PmainClass=io.honeycomb.masterclass.instrumentationtraps.contextpropagation.Before
```

Each variant prints the trace IDs involved, so you can see the bug (or fix)
without Honeycomb open. If the local Collector is running, the same traces land
there too.

## Going deeper on async-boundary

The fix here uses direct parent-child spans via context propagation across a
queue, because it's the simplest correct fix for a single background job or
cron run and matches the slide's wording. For a real high-throughput queue like
Kafka, the book recommends **span links** instead, to avoid the "million
children trace" problem. Do not copy this pattern as-is for a real message
queue without reading that section.
