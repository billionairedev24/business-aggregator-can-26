package ca.northline.region.api;

import ca.northline.shared.CodedEnum;

/**
 * What a merchant in a place must write in French before a listing goes live ({@code region.regions.french_listings},
 * S-116): nothing, a Studio warning, or the warning plus a refusal to submit or publish without the French name and
 * description.
 */
public enum FrenchListings implements CodedEnum {
    OFF,
    WARN,
    REQUIRE;

    /** True when the Studio should tell the merchant that French text is missing. */
    public boolean warns() {
        return this != OFF;
    }

    /** True when submitting or publishing without French text is refused. */
    public boolean required() {
        return this == REQUIRE;
    }
}
