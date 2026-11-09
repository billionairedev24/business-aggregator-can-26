package ca.northline.discovery.application;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Use case: the home page's numbers for a city (S-46), or — when the visitor's place isn't known (S-190: a location is
 * never required) — for every live market of the region model together.
 */
public interface ViewHome {

    /** @param city a city, or null for every live market */
    HomeSummary of(@Nullable String city, Locale locale);
}
