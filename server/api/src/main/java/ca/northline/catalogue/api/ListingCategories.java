package ca.northline.catalogue.api;

import java.util.Collection;
import java.util.Map;

/** S-95: the category of each listing (an offer through its catalogue product, or a service), for the console's sales by category. */
public interface ListingCategories {

    /** Listing id → category id; listings without a category are absent. */
    Map<String, String> categories(Collection<String> listingIds);
}
