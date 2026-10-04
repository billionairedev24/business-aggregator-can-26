package ca.northline.catalogue.application;

import ca.northline.region.api.AgeClass;
import java.util.List;

/** Offers the platform hid for a missing licence ({@code catalogue.offers.licence_hold}, V341). */
public interface LicenceHolds {

    void hold(String offerId, boolean held);

    /** The business's approved, live offers whose category carries the class. */
    List<String> liveOffers(String merchantId, AgeClass ageClass);

    /** The business's offers of the class the platform hid. */
    List<String> heldOffers(String merchantId, AgeClass ageClass);
}
