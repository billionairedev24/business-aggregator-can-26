package ca.northline.console.application;

import ca.northline.payments.api.CardDisputes;
import ca.northline.shared.MerchantScope;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — card disputes (S-156): chargebacks with the issuer's deadline, Stripe's fee and the evidence an
 * agent submits to Stripe (payments), with the business's name and province (merchants, region).
 */
public interface CardDisputeDesk {

    List<Item> list(MerchantScope scope);

    Optional<Detail> detail(String disputeId);

    Detail save(String disputeId, CardDisputes.Draft draft, String staffId, String role);

    Detail submit(String disputeId, String staffId, String role);

    record Item(
            CardDisputes.Row row,
            String businessName,
            @Nullable String province) {}

    record Detail(Item item, CardDisputes.Detail detail) {}
}
