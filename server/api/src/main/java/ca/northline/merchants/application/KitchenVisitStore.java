package ca.northline.merchants.application;

import ca.northline.merchants.domain.KitchenVisit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Outbound port (S-120): {@code merchants.kitchen_visits}. */
public interface KitchenVisitStore {

    void insert(KitchenVisit visit);

    /** Writes status, checklist, note, photos and the outcome. */
    void update(KitchenVisit visit);

    /** Locks the row for an update. */
    Optional<KitchenVisit> lock(String merchantId, String visitId);

    /** Newest first. */
    List<KitchenVisit> visits(String merchantId);

    /** Each business's latest visit (by scheduled time), cancelled ones skipped. */
    Map<String, KitchenVisit> latest(Collection<String> merchantIds);
}
