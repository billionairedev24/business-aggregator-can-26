package ca.northline.payments.api;

import java.util.Optional;

/** The person's default saved card, for the account menu ("Visa ··4471"; S-59). Brand and last four digits only. */
public interface SavedCards {

    /** @param brand Stripe's code, e.g. {@code visa} */
    record CardLabel(String brand, String last4) {}

    Optional<CardLabel> defaultCard(String customerId);
}
