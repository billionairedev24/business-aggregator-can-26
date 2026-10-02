package ca.northline.identity.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** S-95: when accounts were created, for the console's signup-month cohorts (ids and instants only). */
public interface SignupDates {

    /** Of {@code userIds}, those created at or after {@code since}, with when. */
    Map<String, Instant> signedUpSince(Collection<String> userIds, Instant since);
}
