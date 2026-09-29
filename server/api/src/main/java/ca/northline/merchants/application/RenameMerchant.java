package ca.northline.merchants.application;

import ca.northline.merchants.domain.Merchant;

/** Change the customer-facing display name; publishes {@code MerchantRenamed} when it changed. */
public interface RenameMerchant {

    record Command(String merchantId, String displayName, String actorId) {}

    Merchant rename(Command command);
}
