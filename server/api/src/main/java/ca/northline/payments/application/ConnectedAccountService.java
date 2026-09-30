package ca.northline.payments.application;

import ca.northline.payments.api.ConnectedAccounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a merchant's Connect Express account and puts its Stripe payouts on {@code manual}. */
@Service
@RequiredArgsConstructor
@Transactional
class ConnectedAccountService implements ConnectedAccounts {

    private final PayoutRepository payouts;
    private final PayoutGateway gateway;

    @Override
    public void linked(String merchantId, String stripeAccount) {
        if (payouts.linkConnectedAccount(merchantId, stripeAccount)) {
            gateway.useManualPayouts(stripeAccount);
        }
    }
}
