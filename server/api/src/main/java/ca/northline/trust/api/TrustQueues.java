package ca.northline.trust.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Trust &amp; safety work for the console overview (S-91): open flags and businesses below a quality floor. */
public interface TrustQueues {

    /** Open flags (any target; a business's own when the scope is a set of businesses). */
    Flags openFlags(MerchantScope scope);

    /** Businesses whose latest quality score is below {@code floor}. */
    long belowFloor(MerchantScope scope, int floor);

    /**
     * @param offPlatformPayment at least one of them is an off-platform payment flag (design: "includes off-platform
     *     payment")
     */
    record Flags(long count, @Nullable Instant oldest, boolean offPlatformPayment) {}
}
