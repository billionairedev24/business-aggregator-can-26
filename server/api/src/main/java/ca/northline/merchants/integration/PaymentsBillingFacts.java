package ca.northline.merchants.integration;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.application.BusinessSettingsStore;
import ca.northline.payments.api.MerchantBillingFacts;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Answers payments' {@link MerchantBillingFacts} from the merchants directory (S-37) and business settings (S-41). */
@Component
@RequiredArgsConstructor
class PaymentsBillingFacts implements MerchantBillingFacts {

    private final MerchantDirectory directory;
    private final BusinessSettingsStore settings;

    @Override
    public Optional<Billing> billing(String merchantId) {
        return directory.profile(merchantId).map(p -> new Billing(p.tier(), p.takeRateBps(), p.province()));
    }

    @Override
    public Optional<StatementParty> statementParty(String merchantId) {
        return settings.find(merchantId).map(s -> {
            var gst = s.gstNumber();
            return new StatementParty(s.legalName(), s.displayName().value(), gst == null ? null : gst.value());
        });
    }
}
