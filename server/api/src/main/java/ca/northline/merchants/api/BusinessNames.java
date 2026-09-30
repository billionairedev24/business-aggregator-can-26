package ca.northline.merchants.api;

import java.util.Optional;

/** A business's display name, for messages about it (notification emails). Added by S-13. */
public interface BusinessNames {

    Optional<String> displayName(String merchantId);
}
