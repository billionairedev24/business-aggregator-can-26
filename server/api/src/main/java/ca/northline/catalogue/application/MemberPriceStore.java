package ca.northline.catalogue.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-152): {@code catalogue.offers.member_discount_pct}. */
public interface MemberPriceStore {

    /** Empty when the offer isn't the shop's; else its member discount (empty inside when none). */
    Optional<Optional<Integer>> find(String merchantId, String offerId);

    void set(String merchantId, String offerId, @Nullable Integer pct);

    Map<String, Integer> live(Collection<String> offerIds);
}
