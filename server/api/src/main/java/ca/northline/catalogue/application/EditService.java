package ca.northline.catalogue.application;

import ca.northline.catalogue.application.ListingView.ServiceView;
import ca.northline.catalogue.domain.ServiceDetails;
import org.jspecify.annotations.Nullable;

/** Save a service listing as a draft (new or existing). */
public interface EditService {

    record Command(String merchantId, @Nullable String listingId, ServiceDetails details, String actorId) {}

    ServiceView create(Command command);

    ServiceView update(Command command);
}
