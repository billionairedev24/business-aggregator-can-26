package ca.northline.merchants.application;

import ca.northline.merchants.domain.Merchant;
import java.util.Optional;

/** Outbound port for the Merchant aggregate. Implemented in {@code merchants.persistence}. */
public interface MerchantRepository {
    Optional<Merchant> findById(String id);

    Merchant save(Merchant merchant);
}
