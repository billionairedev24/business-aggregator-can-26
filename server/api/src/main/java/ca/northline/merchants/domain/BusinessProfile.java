package ca.northline.merchants.domain;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Per-type public answers of the Business step (design 02 {@code bfSets}); stored as {@code merchants.merchants.profile}
 * jsonb. Option fields hold codes (e.g. {@code yearsOperating = "3-5"}), free-text fields the owner's words.
 */
public record BusinessProfile(
        @Nullable String yearsOperating,
        @Nullable String teamSize,
        @Nullable String serviceArea,
        List<String> workLocations,
        List<String> languages,
        @Nullable String licenceNumbers,
        @Nullable String description,
        @Nullable String productCount,
        @Nullable String pickupAddress,
        @Nullable String sameDayCutoff,
        List<String> inventorySources,
        List<String> perishables,
        List<String> cuisines,
        @Nullable String kitchenAddress,
        @Nullable String ahsPermitNumber,
        @Nullable String cityLicenceNumber,
        @Nullable String seats,
        @Nullable String certifiedHandlers,
        List<String> fulfilment,
        List<String> dietary,
        @Nullable String alcohol) {

    public static final BusinessProfile EMPTY = new BusinessProfile(
            null, null, null, List.of(), List.of(), null, null, null, null, null, List.of(), List.of(), List.of(), null,
            null, null, null, null, List.of(), List.of(), null);

    public BusinessProfile {
        workLocations = copy(workLocations);
        languages = copy(languages);
        inventorySources = copy(inventorySources);
        perishables = copy(perishables);
        cuisines = copy(cuisines);
        fulfilment = copy(fulfilment);
        dietary = copy(dietary);
    }

    /** Free-text places that may name the city the business works from, most specific first. */
    public Stream<String> places() {
        return Stream.of(kitchenAddress, pickupAddress, serviceArea).filter(java.util.Objects::nonNull);
    }

    private static List<String> copy(@Nullable List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
