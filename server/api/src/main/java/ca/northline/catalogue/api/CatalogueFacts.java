package ca.northline.catalogue.api;

import java.util.List;

/** Small catalogue reads other modules need. Names are resolved for {@code lang} with English fallback. */
public interface CatalogueFacts {

    /** Live offers at or below their low-stock threshold, lowest stock first. */
    List<LowStock> lowStock(String merchantId, String lang);

    /** The merchant's bookable services with their duration (Availability › Preview). */
    List<ServiceDuration> services(String merchantId, String lang);

    record LowStock(String offerId, String name, int stock) {}

    record ServiceDuration(String serviceId, String name, int durationMin) {}
}
