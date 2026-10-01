package ca.northline.search.domain;

import ca.northline.shared.CodedEnum;

/**
 * Result order. {@code relevance} = text match × trust tier × rating × nearness (when a location is given);
 * {@code distance} needs a location; prices missing (quote-priced services) sort last.
 */
public enum SearchSort implements CodedEnum {
    RELEVANCE,
    DISTANCE,
    PRICE_ASC,
    PRICE_DESC,
    RATING
}
