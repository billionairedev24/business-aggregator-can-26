package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistrySource;

/**
 * {@code northline.registries.<source>.provider=manual}: no API account for this source — every lookup goes to a
 * Northline agent (for Alberta: a registry-agent search), who records the search's reference when deciding the review.
 */
class ManualRegistry implements BusinessRegistry {

    private final RegistrySource source;

    ManualRegistry(RegistrySource source) {
        this.source = source;
    }

    @Override
    public RegistrySource source() {
        return source;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        return new Answer.Manual(source == RegistrySource.ALBERTA_CORPORATE_REGISTRY ? "registry-agent search" : null);
    }
}
