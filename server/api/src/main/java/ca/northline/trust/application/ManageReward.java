package ca.northline.trust.application;

import ca.northline.trust.domain.MerchantReward;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** S-75: the Studio page's "Provider-funded reward" field — read it, switch it on (with its terms) or off. */
public interface ManageReward {

    Optional<MerchantReward> of(String merchantId);

    MerchantReward save(Command command);

    record Command(
            String merchantId,
            String actorId,
            String role,
            boolean active,
            int multiplier,
            @Nullable String label,
            @Nullable LocalDate endsOn) {}
}
