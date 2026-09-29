package ca.northline.merchants.application;

import ca.northline.merchants.domain.Merchant;

/** Studio header summary for one business. Throws {@link ca.northline.shared.NotFound}. */
public interface ViewMerchant {
    Merchant view(String merchantId);
}
