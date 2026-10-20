package ca.northline.catalogue.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-152: a shop's Northline Plus member price on one of its products — a percentage off its price and every variant's,
 * 1–50 %, or none. Only the shop sets it (it sells for less: tax is on the member price); product cards and checkout
 * show it, and the cart prices a member's lines with it.
 */
public interface MemberPrices {

    String RANGE = "Choose a member price between 1% and 50% off, or none.";

    /** The product's member discount; empty when it has none. @throws ca.northline.shared.NotFound not the shop's */
    Optional<Integer> of(String merchantId, String offerId);

    /** Sets ({@code pct}) or removes (null) the member price, audit-logged. */
    Optional<Integer> set(String merchantId, String offerId, @Nullable Integer pct, String actorId, String role);

    /** The member discounts of the approved, live offers among {@code offerIds} that have one. */
    Map<String, Integer> live(Collection<String> offerIds);
}
