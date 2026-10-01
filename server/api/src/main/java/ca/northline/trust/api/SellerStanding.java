package ca.northline.trust.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Trust facts per business for the console's sellers directory (S-82): latest quality score and open flags. */
public interface SellerStanding {

    /** Every asked business is in the map (no score and no flags when there is nothing). */
    Map<String, Standing> of(Collection<String> merchantIds);

    /**
     * @param quality the latest nightly quality score, null before the first
     * @param openFlags the rules of the business's open flags ({@code off_platform_payment}, {@code floor_breach}…)
     */
    record Standing(@Nullable Integer quality, List<String> openFlags) {
        public Standing {
            openFlags = List.copyOf(openFlags);
        }
    }
}
