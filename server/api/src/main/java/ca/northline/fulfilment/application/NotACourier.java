package ca.northline.fulfilment.application;

import ca.northline.fulfilment.domain.DeliveryRules;

/** The caller has no active courier record: 403 {@code not_a_courier} on the courier app's API. */
public final class NotACourier extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotACourier() {
        super(DeliveryRules.NOT_A_COURIER);
    }
}
