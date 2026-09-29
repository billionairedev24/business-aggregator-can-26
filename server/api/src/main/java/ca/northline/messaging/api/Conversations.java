package ca.northline.messaging.api;

import org.jspecify.annotations.Nullable;

/**
 * Opens (or finds) the customer thread of a booking, order or dispute. Booking and orders call it when a job or order
 * is placed; trust when Northline writes to a business about a dispute. Idempotent per {@code (merchantId, refType,
 * refId)}.
 */
public interface Conversations {

    /**
     * @param kind {@code customer} (with a customer) or {@code support} (Northline writes to the business)
     * @param refType {@code booking | order | quote | dispute}
     * @param refCode human code shown in the Studio: {@code BK-7712}, {@code NL-48213}, {@code DS-1188}
     * @param counterpartName display name of the other side: "Amara Osei", "Northline support"
     * @param subject short context: "brake inspection Tue 9:00"
     * @param assigneeId team member on the job (technicians see only their own threads), if any
     */
    record Open(
            String merchantId,
            String kind,
            String refType,
            String refId,
            String refCode,
            String counterpartId,
            String counterpartName,
            @Nullable String subject,
            @Nullable String assigneeId) {}

    /** The thread id. */
    String open(Open command);
}
