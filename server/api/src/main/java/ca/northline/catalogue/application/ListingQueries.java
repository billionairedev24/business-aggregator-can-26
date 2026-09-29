package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingKind;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Outbound port: the listings table read model (services ∪ offers). */
public interface ListingQueries {

    List<ListingSummary> list(String merchantId, @Nullable ListingKind kind, int limit, Locale locale);

    int count(String merchantId);
}
