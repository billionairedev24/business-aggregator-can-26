package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingKind;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The listings table: a merchant's services and/or products, newest first. */
public interface BrowseListings {
    List<ListingSummary> list(String merchantId, @Nullable ListingKind kind, int limit, Locale locale);
}
