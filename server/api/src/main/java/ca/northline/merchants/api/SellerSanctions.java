package ca.northline.merchants.api;

import java.util.List;

/**
 * The automatic consequences of the trust rules on a business (S-82, rules of S-93), applied by the console's nightly
 * enforcement as the {@value #SYSTEM} actor: each is recorded in the business's oversight trail and the platform audit
 * log, announced (search re-reads the business) and emailed to the owners with the reason, like a staff action.
 */
public interface SellerSanctions {

    /** The actor id of automatic consequences. */
    String SYSTEM = "system";

    /** Hides an active business from search for {@code cause}; false when it already was hidden (any cause). */
    boolean hideFromSearch(String merchantId, String cause, String reason);

    /** Shows it again when it was hidden for {@code cause}; false otherwise (staff hid it, or it isn't hidden). */
    boolean restoreSearch(String merchantId, String cause, String reason);

    /** Suspends an active or paused business; false when it isn't. */
    boolean suspend(String merchantId, String reason);

    /** Businesses hidden from search for {@code cause}. */
    List<String> hiddenFromSearch(String cause);
}
