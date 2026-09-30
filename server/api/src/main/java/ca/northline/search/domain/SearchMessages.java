package ca.northline.search.domain;

/** Validation messages of the search API (not in validation-rules.md: search has no form). */
public final class SearchMessages {

    public static final int MAX_QUERY = 100;
    public static final int MAX_SIZE = 50;
    public static final int MAX_SUGGESTIONS = 10;
    public static final double MAX_RADIUS_KM = 100;

    public static final String QUERY_TOO_LONG = "Search for 100 characters or fewer.";
    public static final String MARKET = "Choose a province: AB, BC, ON or QC.";
    public static final String KIND = "Choose service, product, food or merchant.";
    public static final String TIER = "Choose registered, trusted or master.";
    public static final String SORT = "Sort by relevance, distance, price_asc, price_desc or rating.";
    public static final String LAT = "Latitude must be between -90 and 90.";
    public static final String LNG = "Longitude must be between -180 and 180.";
    public static final String LOCATION_PAIR = "Send both lat and lng, or neither.";
    public static final String RADIUS = "Choose a distance between 1 and 100 km.";
    public static final String RADIUS_NEEDS_LOCATION = "A distance filter needs your location (lat and lng).";
    public static final String DISTANCE_NEEDS_LOCATION = "Sorting by distance needs your location (lat and lng).";
    public static final String PRICE = "Prices can't be negative.";
    public static final String PRICE_ORDER = "The lowest price is above the highest.";
    public static final String RATING = "Choose a rating between 1 and 5.";
    public static final String SIZE = "Ask for 1 to 50 results.";
    public static final String SUGGEST_SIZE = "Ask for 1 to 10 suggestions.";
    public static final String AFTER = "This page link no longer works. Start the search again.";
    public static final String PREFIX_REQUIRED = "Type at least one letter.";

    private SearchMessages() {}
}
