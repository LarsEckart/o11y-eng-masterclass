package io.honeycomb.masterclass.slisslos.scenario;

/**
 * One of the two endpoints in the dataset. The Go version uses a string type
 * with two constants; an enum gives the same "closed set" in Java.
 */
public enum Route {
    ORDERS("/api/orders"),
    HEALTHZ("/healthz");

    private final String path;

    Route(String path) {
        this.path = path;
    }

    /** The URL path, which becomes the {@code http.route} and {@code url.path} attributes. */
    public String path() {
        return path;
    }
}
