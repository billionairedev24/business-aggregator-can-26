package ca.northline.availability.application;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the service zones a market offers providers ({@code availability.service_zones.market_id}, V131) —
 * region data, so a provider in any market picks its own market's zones.
 */
public interface MarketZones {

    /** Zone names of the market, in display order; none for no market. */
    List<String> offered(@Nullable String marketId);

    /** The zones a provider of the market starts with before saving its own booking rules. */
    Set<String> defaults(@Nullable String marketId);
}
