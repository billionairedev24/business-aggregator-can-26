package ca.northline.payments.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Disputes per business, for the console's sellers directory (S-82): its dispute rate. */
public interface DisputeCounts {

    /** Disputes opened in [from, to) per business; businesses without any are absent. */
    Map<String, Long> opened(Collection<String> merchantIds, Instant from, Instant to);
}
