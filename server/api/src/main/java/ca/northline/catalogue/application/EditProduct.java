package ca.northline.catalogue.application;

import ca.northline.catalogue.application.ListingView.ProductView;
import ca.northline.catalogue.domain.ProductDetails;
import org.jspecify.annotations.Nullable;

/**
 * Save a product listing as a draft (new or existing). GTIN-matched products attach to the shared catalogue record
 * and inherit its content; unmatched GTINs create the shared record; no GTIN means a seller-owned record.
 */
public interface EditProduct {

    record Command(String merchantId, @Nullable String listingId, ProductDetails details, String actorId) {}

    ProductView create(Command command);

    ProductView update(Command command);
}
