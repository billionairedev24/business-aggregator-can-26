package ca.northline.merchants.api;

import java.util.Collection;
import java.util.List;

/**
 * The businesses a customer can buy goods from (S-49, consumer Shop): active sellers (type {@code seller} or
 * {@code both}) of a market, with what the Shop pages show about them. A market is a city (Calgary, Edmonton, Airdrie —
 * design 06 Location screen); it matches {@code merchants.merchants.city} ignoring case.
 */
public interface ShopDirectory {

    /** Active sellers whose city is {@code market}, by name. */
    List<Shop> shopsIn(String market);

    /** The given businesses when they are active sellers (any market); others are left out. */
    List<Shop> shops(Collection<String> merchantIds);

    /**
     * @param tier {@code registered} | {@code trusted} | {@code master}
     * @param city the market the business trades in (display form, e.g. "Calgary")
     */
    record Shop(String merchantId, String displayName, String tier, String city) {}
}
