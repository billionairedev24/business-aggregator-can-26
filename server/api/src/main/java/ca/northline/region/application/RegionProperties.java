package ca.northline.region.application;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.region}: the configuration overrides on top of the region rows (S-134, docs/runbooks/regions.md).
 *
 * @param provinces provinces served whatever their row says, in order: {@code CODE[=Zone/Id],…} — a zone given here
 *     replaces the province's ({@code REGION_PROVINCES}, falling back to S-44's {@code SEARCH_MARKETS})
 * @param defaultProvince the province used when nothing is known about a place; must be one of {@code provinces} or a
 *     live row to be served; blank = none ({@code REGION_DEFAULT_PROVINCE}, falling back to {@code SEARCH_DEFAULT_MARKET})
 * @param platformZone the zone of work that belongs to no market: nightly jobs, support hours, account dates
 *     ({@code REGION_PLATFORM_ZONE})
 * @param cacheTtl how long region rows are kept before they are read again ({@code REGION_CACHE_TTL})
 * @param frenchFirst provinces (two-letter codes) or market ids that are French-first whatever their row says, with
 *     French listing text required — for an environment that must behave like a French-first place before operations
 *     set the rows ({@code REGION_FRENCH_FIRST}, S-116); blank = the rows decide
 */
@ConfigurationProperties("northline.region")
record RegionProperties(
        @DefaultValue("") String provinces,
        @DefaultValue("") String defaultProvince,
        ZoneId platformZone,
        @DefaultValue("60s") Duration cacheTtl,
        @DefaultValue("") String frenchFirst) {}
