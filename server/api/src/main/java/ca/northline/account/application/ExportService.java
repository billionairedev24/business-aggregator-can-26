package ca.northline.account.application;

import ca.northline.account.application.Favourites.ManageFavourites;
import ca.northline.account.application.Preferences.ManagePreferences;
import ca.northline.identity.api.AccountFacts;
import ca.northline.identity.api.DeliveryAddresses;
import ca.northline.payments.api.CustomerCaseQuery;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ExportMyData}: composed from identity, account, orders, booking and payments through their APIs. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ExportService implements ExportMyData {

    private final AccountFacts identity;
    private final DeliveryAddresses addresses;
    private final ManagePreferences preferences;
    private final ManageFavourites favourites;
    private final ViewActivity activity;
    private final ViewWallet wallet;
    private final CustomerCaseQuery cases;
    private final Clock clock;

    @Override
    public Export of(String userId) {
        return new Export(
                clock.instant(),
                identity.profile(userId),
                addresses.of(userId),
                preferences.view(userId),
                favourites.list(userId),
                activity.items(userId),
                wallet.wallet(userId),
                cases.cases(userId, 500));
    }
}
