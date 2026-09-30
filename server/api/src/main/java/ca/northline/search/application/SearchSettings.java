package ca.northline.search.application;

import java.time.Duration;

/** Settings the application layer needs (bound from {@code northline.search.*} in the integration package). */
public record SearchSettings(Duration cacheTtl, String defaultMarket, int rateLimitPerMinute) {}
