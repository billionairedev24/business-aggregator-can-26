package ca.northline.payments.application;

import ca.northline.payments.api.ConnectAccountUpdated;
import ca.northline.payments.api.ConnectedAccounts;
import ca.northline.shared.Ids;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a merchant's Connect Express account and puts its Stripe payouts on {@code manual}. */
@Service
@RequiredArgsConstructor
@Transactional
class ConnectedAccountService implements ConnectedAccounts {

    private final PayoutRepository payouts;
    private final PayoutGateway gateway;
    private final ApplicationEventPublisher events;

    @Override
    public void linked(String merchantId, String stripeAccount) {
        if (payouts.linkConnectedAccount(merchantId, stripeAccount)) {
            gateway.useManualPayouts(stripeAccount);
        }
    }

    /**
     * {@code account.updated}: charges / payouts enabled, open requirements, instant eligibility of the default bank
     * account. An event older than the last one applied changes nothing. The account is recorded when Stripe's metadata
     * names the merchant and payments didn't know it yet.
     */
    boolean stripeAccountUpdated(StripeEvent event) {
        var o = event.object();
        var account = o.id();
        if (account == null) {
            return false;
        }
        var banks = o.objects("external_accounts", "data");
        Boolean instant = banks.isEmpty()
                ? null
                : banks.stream()
                        .filter(b -> b.flag("default_for_currency"))
                        .findFirst()
                        .orElse(banks.getFirst())
                        .strings("available_payout_methods")
                        .contains("instant");
        var status = new PayoutRepository.AccountStatus(
                account,
                o.text("metadata", "northline_merchant_id"),
                o.flag("charges_enabled"),
                o.flag("payouts_enabled"),
                instant,
                o.strings("requirements", "currently_due").size(),
                o.strings("requirements", "past_due").size(),
                o.text("requirements", "disabled_reason"),
                event.created());
        payouts.updateConnectedAccount(status)
                .ifPresent(merchantId -> events.publishEvent(new ConnectAccountUpdated(
                        Ids.next(),
                        event.created(),
                        merchantId,
                        account,
                        status.chargesEnabled(),
                        status.payoutsEnabled(),
                        status.requirementsDue(),
                        status.requirementsPastDue(),
                        status.disabledReason())));
        return true;
    }
}
