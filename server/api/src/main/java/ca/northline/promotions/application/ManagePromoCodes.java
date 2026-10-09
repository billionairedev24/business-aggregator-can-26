package ca.northline.promotions.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Console (finance): make promo codes, list them with their use, switch them off and on. */
public interface ManagePromoCodes {

    record NewCode(
            String code,
            @Nullable String description,
            @Nullable String kind,
            @Nullable Integer percent,
            @Nullable Long amountCents,
            @Nullable Long maxDiscountCents,
            @Nullable Long minSpendCents,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Integer perCustomerLimit,
            @Nullable Integer totalLimit,
            @Nullable String fundedBy,
            @Nullable String merchantId,
            List<String> appliesTo) {
        public NewCode {
            appliesTo = List.copyOf(appliesTo);
        }
    }

    /**
     * @param state {@code scheduled} | {@code live} | {@code ended} | {@code off}
     * @param merchantName the funding business's name, for a merchant-funded code
     */
    record CodeView(
            String id,
            String code,
            @Nullable String description,
            String kind,
            @Nullable Integer percent,
            @Nullable Long amountCents,
            @Nullable Long maxDiscountCents,
            long minSpendCents,
            Instant startsAt,
            Instant endsAt,
            int perCustomerLimit,
            @Nullable Integer totalLimit,
            String fundedBy,
            @Nullable String merchantId,
            @Nullable String merchantName,
            List<String> appliesTo,
            boolean active,
            String state,
            int redeemed,
            long discountCents) {
        public CodeView {
            appliesTo = List.copyOf(appliesTo);
        }
    }

    List<CodeView> list();

    CodeView create(NewCode code, String staffId, String role);

    CodeView setActive(String id, boolean active, String staffId, String role);
}
