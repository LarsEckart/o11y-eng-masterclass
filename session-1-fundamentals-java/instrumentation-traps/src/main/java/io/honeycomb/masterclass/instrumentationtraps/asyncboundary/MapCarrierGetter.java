package io.honeycomb.masterclass.instrumentationtraps.asyncboundary;

import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.Map;

enum MapCarrierGetter implements TextMapGetter<Map<String, String>> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Map<String, String> carrier) {
        return carrier.keySet();
    }

    @Override
    public String get(Map<String, String> carrier, String key) {
        if (carrier == null || key == null) {
            return null;
        }
        return carrier.get(key);
    }
}
