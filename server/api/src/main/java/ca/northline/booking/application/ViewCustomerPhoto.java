package ca.northline.booking.application;

import ca.northline.booking.api.CustomerPhotos.Content;
import java.util.Optional;

/** S-153: a customer's photo as the business sees it — only on a request sent to it or one of its bookings. */
public interface ViewCustomerPhoto {

    Optional<Content> forMerchant(String merchantId, String photoId);
}
