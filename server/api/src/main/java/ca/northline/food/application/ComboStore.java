package ca.northline.food.application;

import ca.northline.food.domain.ComboPricing;
import ca.northline.food.domain.ComboStatus;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code food.combos}. */
public interface ComboStore {

    List<ComboRow> combos(String merchantId);

    Optional<ComboRow> combo(String merchantId, String comboId);

    void insert(ComboRow combo);

    void update(ComboRow combo);

    void delete(String merchantId, String comboId);

    /** A slot: "Any 2 mains" = qty 2 of any item in section {@code sectionId}, or of the listed {@code itemIds}. */
    record Slot(String label, int qty, @Nullable String sectionId, List<String> itemIds) {}

    /** Availability window of a deal (ISO weekdays, local times). */
    record Window(List<Integer> days, String from, String to) {}

    @Builder(toBuilder = true)
    record ComboRow(
            String id,
            String merchantId,
            String name,
            List<Slot> slots,
            ComboPricing pricing,
            @Nullable Long priceCents,
            @Nullable Integer discountBps,
            @Nullable Window schedule,
            ComboStatus status,
            boolean swapsAllowed) {}
}
