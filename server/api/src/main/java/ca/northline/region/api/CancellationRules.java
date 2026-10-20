package ca.northline.region.api;

import org.jspecify.annotations.Nullable;

/**
 * S-153: late-cancellation defaults and limits for bookings in a place — region data
 * ({@code region.regions.cancel_*}): the market's value, else its province's, else the platform configuration
 * ({@code northline.region.cancellation.*}). Code never asks "is this province X": a place whose consumer rules cap
 * cancellation fees sets its max here.
 */
public interface CancellationRules {

    /**
     * @param windowHours the default free-cancellation window (cancelling less than this before the start costs a fee)
     * @param feeBps the default fee, in basis points of the booking's price (0 = none)
     * @param maxWindowHours the longest window a provider may set
     * @param maxFeeBps the highest fee a provider may set
     */
    record Rules(int windowHours, int feeBps, int maxWindowHours, int maxFeeBps) {
        public Rules {
            maxWindowHours = Math.max(0, maxWindowHours);
            maxFeeBps = Math.clamp(maxFeeBps, 0, 10_000);
            windowHours = Math.clamp(windowHours, 0, maxWindowHours);
            feeBps = Math.clamp(feeBps, 0, maxFeeBps);
        }
    }

    /**
     * @param province the business's province code, or null
     * @param marketId the business's region market id, or null
     */
    Rules of(@Nullable String province, @Nullable String marketId);
}
