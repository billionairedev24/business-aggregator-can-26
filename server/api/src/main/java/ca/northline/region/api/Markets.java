package ca.northline.region.api;

import java.time.ZoneId;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The markets Northline serves and the time zone each one keeps (DECISIONS "Region-neutral by design"). Read from the
 * same configuration as search ({@code SEARCH_MARKETS} = {@code CODE=Zone/Id,…}, {@code SEARCH_DEFAULT_MARKET}) until
 * S-134 moves it into the region configuration — one list, not one per feature.
 */
public interface Markets {

    /** Province/territory codes served (e.g. {@code AB}), in configuration order. */
    Set<String> served();

    boolean serves(@Nullable String province);

    /** The market searched and shown when nothing else is known ({@code SEARCH_DEFAULT_MARKET}); null = none set. */
    @Nullable
    String defaultProvince();

    /**
     * The time zone of the province's market; for a province that isn't served (or unknown, {@code null}) the default
     * market's, else the first configured market's.
     */
    ZoneId zone(@Nullable String province);
}
