package ca.northline.restricted.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The ID check at the handoff of an order with age-restricted items, recorded by whoever hands it over: the courier at
 * the door (fulfilment) or the business at the counter (food pickup). Only what was confirmed is kept — no ID image,
 * number or date of birth. Call it inside the transaction that hands the order over or refuses it.
 */
public interface HandoffChecks {

    /** Refusal reasons, as the apps show them. */
    List<String> REASONS = List.of("no_id", "underage", "mismatch", "nobody_of_age", "intoxicated", "other");

    String CONFIRM = "Confirm you checked government photo ID, the name matches and the person is of age.";
    String REASON = "Choose why the order can't be handed over.";

    /** @return the check's id */
    String record(Check check);

    /**
     * @param orderType {@code goods | food}
     * @param merchantId the business, for a counter check
     * @param province the province whose rules set {@code requiredAge}
     * @param actorRole {@code courier | merchant}
     * @param place {@code door | counter}
     * @param reason null when passed; one of {@link #REASONS} when refused
     */
    record Check(
            String orderId,
            String orderType,
            @Nullable String merchantId,
            @Nullable String province,
            int requiredAge,
            String actorId,
            String actorRole,
            String place,
            boolean idChecked,
            boolean recipientMatches,
            boolean ofAge,
            @Nullable String reason,
            Instant at) {

        public boolean passed() {
            return reason == null;
        }
    }
}
