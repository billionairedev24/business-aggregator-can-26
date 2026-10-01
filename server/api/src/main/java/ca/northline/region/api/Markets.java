package ca.northline.region.api;

import java.time.ZoneId;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The provinces Northline serves and the time zone each one keeps (DECISIONS "Region-neutral by design"): those listed
 * in {@code REGION_PROVINCES} ({@code CODE[=Zone/Id],…}; S-44's {@code SEARCH_MARKETS} still read as its fallback) plus
 * those whose region row is live, with {@code REGION_DEFAULT_PROVINCE}. The same {@link Regions} model, by province —
 * one list, not one per feature.
 */
public interface Markets {

    /** Province/territory codes served (e.g. {@code AB}), in configuration order. */
    Set<String> served();

    boolean serves(@Nullable String province);

    /** The province searched and shown when nothing else is known ({@code REGION_DEFAULT_PROVINCE}); null = none. */
    @Nullable
    String defaultProvince();

    /**
     * The time zone of the province's market; for a province that isn't served (or unknown, {@code null}) the default
     * market's, else the first configured market's.
     */
    ZoneId zone(@Nullable String province);
}
