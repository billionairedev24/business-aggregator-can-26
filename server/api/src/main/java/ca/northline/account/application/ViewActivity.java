package ca.northline.account.application;

import ca.northline.account.domain.ActivityAction;
import ca.northline.account.domain.ActivityKind;
import ca.northline.account.domain.ActivityStatus;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** "Orders &amp; bookings" (design 06 {@code orders}): every order, booking and open quote request, newest first. */
public interface ViewActivity {

    /** How many rows the list holds at most (the oldest drop off). */
    int LIMIT = 100;

    /**
     * @param title the job or request title (bookings, quotes); orders are titled by the web from {@code delivery}
     *     and {@code shops}
     * @param with the businesses (shops, kitchen, provider · member first name, providers quoting)
     * @param delivery {@code pooled | direct} (shop order), {@code delivery | pickup} (food), else null
     * @param when the delivery window or ETA, the job's start, the request's preferred date — else when it happened
     * @param whenEnd the end of a delivery window, else null
     * @param active still under way (the "Active" filter); the rest is "Past"
     * @param caseRef the newest refund case or dispute on it (the "Refunds &amp; cases" filter)
     * @param href the consumer route the action opens, or null when there is nowhere to go
     */
    record Item(
            String id,
            ActivityKind kind,
            @Nullable String ref,
            String title,
            List<String> with,
            @Nullable String delivery,
            int shops,
            int items,
            Instant when,
            @Nullable Instant whenEnd,
            long amountCents,
            ActivityStatus status,
            boolean active,
            @Nullable CaseRef caseRef,
            ActivityAction action,
            @Nullable String href,
            Instant sortAt) {

        public Item {
            with = List.copyOf(with);
        }

        public String tone() {
            return status.tone();
        }
    }

    /** @param open still being looked at */
    record CaseRef(String id, String number, String kind, boolean open) {}

    List<Item> items(String userId);
}
