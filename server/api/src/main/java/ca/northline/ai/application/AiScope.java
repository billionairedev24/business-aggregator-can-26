package ca.northline.ai.application;

import ca.northline.ai.api.AiFeature;

/** The feature a model call belongs to, bound around each call so the port's metrics and traces can tag it. */
final class AiScope {
    private AiScope() {}

    static final ScopedValue<AiFeature> FEATURE = ScopedValue.newInstance();

    static String feature() {
        return FEATURE.isBound() ? FEATURE.get().code() : "unknown";
    }
}
