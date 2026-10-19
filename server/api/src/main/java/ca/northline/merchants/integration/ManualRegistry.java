package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistrySource;
import org.jspecify.annotations.Nullable;

/**
 * {@code northline.registries.<source>.provider=manual}: no API account for this source — every lookup goes to a
 * Northline agent (for a province without a registry API: a registry-agent search), who records the search's reference when deciding the review.
 */
class ManualRegistry implements BusinessRegistry {

    private final RegistrySource source;
    private final @Nullable String how;

    ManualRegistry(RegistrySource source) {
        this(source, null);
    }

    /** @param how what the agent does, shown with the review (a province's registry-agent search); null = nothing */
    ManualRegistry(RegistrySource source, @Nullable String how) {
        this.source = source;
        this.how = how;
    }

    @Override
    public RegistrySource source() {
        return source;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        return new Answer.Manual(how);
    }
}
