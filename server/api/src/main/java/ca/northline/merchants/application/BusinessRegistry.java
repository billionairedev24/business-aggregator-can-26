package ca.northline.merchants.application;

import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistrySource;

/**
 * Outbound port: one business registry (S-23). One adapter per {@link RegistrySource} — the federal corporation API, a
 * province's corporate registry, a city's business-licence dataset — chosen by
 * {@code northline.registries.<source>.provider}; {@code fixtures} under {@code local}/{@code test}. Which adapters
 * serve a business is the region model's (its province's and city's registries, S-134). docs/runbooks/registries.md.
 */
public interface BusinessRegistry {

    RegistrySource source();

    /**
     * Looks the number up. Returns {@link Answer.Unavailable} (never throws) when the source can't be reached, so the
     * lookup lands with an agent instead of failing the owner's request.
     */
    Answer lookup(RegistryQuery query);

    /** Licence names (any case) this source answers besides its business records; none by default. */
    default java.util.Set<String> licences() {
        return java.util.Set.of();
    }
}
