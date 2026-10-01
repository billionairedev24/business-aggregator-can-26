package ca.northline.merchants.domain;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Which registry adapters serve a business (S-134): picked by its province and its city from the region model
 * ({@code region.regions.registries}), never by default. A province without a provincial registry adapter has its
 * records checked by a Northline agent ({@link RegistrySource#MANUAL}); a city without a municipal licence source has
 * no municipal lookup.
 *
 * @param provincial the province's corporate registry, null = checked by hand
 * @param municipal the city's business-licence source, null = none
 * @param municipalLicences licence names (lower case) the municipal source answers, e.g. a city's mobile vending permit
 */
public record RegistryRoutes(
        @Nullable RegistrySource provincial, @Nullable RegistrySource municipal, Set<String> municipalLicences) {

    public static final RegistryRoutes NONE = new RegistryRoutes(null, null, Set.of());

    public RegistryRoutes {
        municipalLicences =
                municipalLicences.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    RegistrySource provincialOrManual() {
        return provincial != null ? provincial : RegistrySource.MANUAL;
    }

    boolean municipalAnswers(String licence) {
        return municipal != null && municipalLicences.contains(licence.toLowerCase(Locale.ROOT));
    }
}
