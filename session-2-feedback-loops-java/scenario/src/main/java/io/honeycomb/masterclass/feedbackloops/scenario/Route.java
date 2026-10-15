package io.honeycomb.masterclass.feedbackloops.scenario;

/** One of the two endpoints in the dataset. */
public enum Route {
    CHECKOUT("/api/checkout"),
    BILLING("/api/billing");

    private final String path;

    Route(String path) {
        this.path = path;
    }

    /** The URL path, which is also the value of the {@code http.route} attribute. */
    public String path() {
        return path;
    }
}
