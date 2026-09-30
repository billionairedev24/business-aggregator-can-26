package ca.northline.merchants.application;

import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistrySource;

/**
 * Outbound port: one business registry (S-23). One adapter per {@link RegistrySource} — the ISED Federal Corporation
 * API, the Alberta Corporate Registry through a search service (or manual), the City of Calgary business-licence
 * dataset — chosen by {@code northline.registries.<source>.provider}; {@code fixtures} under {@code local}/{@code test}.
 * docs/runbooks/registries.md.
 */
public interface BusinessRegistry {

    RegistrySource source();

    /**
     * Looks the number up. Returns {@link Answer.Unavailable} (never throws) when the source can't be reached, so the
     * lookup lands with an agent instead of failing the owner's request.
     */
    Answer lookup(RegistryQuery query);
}
