# seed-sli-service (Java)

Backfills the `sli-demo-service` Honeycomb dataset for Masterclass 4: a mostly
healthy service with an ongoing incident for enterprise customers on build
`1.4.2`. One root span per request, at an explicit historical timestamp.

```bash
../gradlew :seed-sli-service:run
```

Run it shortly before you go live (see the [session README](../README.md)).
The scenario and its tuning live in [`../scenario/`](../scenario).

The delivery test seeds more spans than the batch processor can queue, against
a deliberately slow in-process receiver, and counts what arrives. It fails if
the seeder stops flushing in chunks.
