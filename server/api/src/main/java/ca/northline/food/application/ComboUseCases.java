package ca.northline.food.application;

import ca.northline.food.application.ComboStore.Slot;
import ca.northline.food.application.ComboStore.Window;
import ca.northline.food.domain.ComboPricing;
import ca.northline.food.domain.ComboStatus;
import ca.northline.food.domain.KitchenPromo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Modifiers &amp; combos → Combos &amp; deals, and the Northline-funded promos panel. */
public final class ComboUseCases {
    private ComboUseCases() {}

    public interface ListCombos {
        List<ComboView> combos(String merchantId);
    }

    public interface EditCombos {
        ComboView create(String merchantId, ComboCommand command);

        ComboView update(String merchantId, String comboId, ComboCommand command);

        void delete(String merchantId, String comboId);
    }

    /** Opt in / out of a Northline-funded promo (owner only). */
    public interface KitchenPromos {
        List<PromoView> promos(String merchantId);

        PromoView set(String merchantId, KitchenPromo promo, boolean enabled, String actorId);
    }

    /** {@code discountPct} for {@code percent_off}, {@code priceCents} for {@code fixed}. */
    public record ComboCommand(
            String name,
            List<Slot> slots,
            ComboPricing pricing,
            @Nullable Long priceCents,
            @Nullable Integer discountPct,
            @Nullable Window schedule,
            ComboStatus status,
            boolean swapsAllowed) {}

    /**
     * {@code rule} = the slot labels joined ("Any 2 mains + 2 spring rolls + 2 drinks"); {@code priceCents} the price
     * charged; {@code referenceCents} the items bought separately (cheapest eligible item per slot); {@code savingCents}
     * the difference ("Save $6").
     */
    public record ComboView(
            String id,
            String name,
            String rule,
            List<Slot> slots,
            ComboPricing pricing,
            long priceCents,
            @Nullable Integer discountPct,
            long referenceCents,
            long savingCents,
            @Nullable Window schedule,
            ComboStatus status,
            boolean swapsAllowed) {}

    public record PromoView(KitchenPromo promo, boolean enabled) {}
}
