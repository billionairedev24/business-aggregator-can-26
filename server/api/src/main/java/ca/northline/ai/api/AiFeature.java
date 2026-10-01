package ca.northline.ai.api;

import java.util.Locale;

/**
 * Every AI feature, for per-feature models ({@code northline.ai.models.<code>}), metrics tags, usage records and the
 * audit trail. {@link Tier#LIGHT} features default to the cheaper model ({@code OPENROUTER_LIGHT_MODEL}),
 * {@link Tier#STANDARD} ones to {@code OPENROUTER_MODEL}.
 */
public enum AiFeature {
    /** Platform checks (the live eval's smoke questions). */
    PLATFORM(Tier.LIGHT),
    /** S-130: the Studio assistant (chat with tools). */
    ASSISTANT(Tier.STANDARD),
    /** S-130: short insights on the dashboard, earnings and listings screens. */
    INSIGHT(Tier.LIGHT),
    /** S-131: listing titles and descriptions in English and French. */
    LISTING_COPY(Tier.STANDARD),
    /** S-131: suggested quote lines from a job request. */
    QUOTE_LINES(Tier.STANDARD),
    /** S-131: reply suggestions in Messages. */
    MESSAGE_REPLY(Tier.LIGHT),
    /** S-131: review summaries. */
    REVIEW_SUMMARY(Tier.LIGHT),
    /** S-132: natural-language search → filters. */
    SEARCH_FILTERS(Tier.LIGHT),
    /** S-132: "something's wrong" triage. */
    HELP_TRIAGE(Tier.LIGHT),
    /** S-133: listing, review and message screening for trust &amp; safety. */
    TRUST_SCREEN(Tier.LIGHT),
    /** S-133: the weekly anomaly scan's explanations. */
    ANOMALY_SCAN(Tier.STANDARD);

    /** Which default model a feature uses. */
    public enum Tier {
        LIGHT,
        STANDARD
    }

    private final Tier tier;

    AiFeature(Tier tier) {
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }

    /** {@code listing_copy}: metrics tag, configuration key, usage record. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
