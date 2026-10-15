package io.honeycomb.masterclass.feedbackloops.scenario;

import java.time.Duration;

/** One child span within a request. */
public record Step(String name, Duration duration) {
}
