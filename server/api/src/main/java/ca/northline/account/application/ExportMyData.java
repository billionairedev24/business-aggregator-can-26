package ca.northline.account.application;

import ca.northline.account.application.Favourites.Favourite;
import ca.northline.account.application.Preferences.Prefs;
import ca.northline.account.application.ViewActivity.Item;
import ca.northline.account.application.ViewWallet.Wallet;
import ca.northline.identity.api.AccountFacts.OwnProfile;
import ca.northline.identity.api.DeliveryAddresses.Address;
import ca.northline.payments.api.CustomerCaseQuery.CaseSummary;
import java.time.Instant;
import java.util.List;

/**
 * "Download my data" (design 06 security): the person's own account data as one JSON document — profile, addresses,
 * preferences, favourites, orders &amp; bookings, points and Plus, refund cases. Sign-in data (passkeys, sessions)
 * lives in northline-auth and is shown on the Security tab; card numbers are never held by Northline.
 */
public interface ExportMyData {

    /** The export, field by field. */
    record Export(
            Instant exportedAt,
            OwnProfile profile,
            List<Address> addresses,
            Prefs preferences,
            List<Favourite> favourites,
            List<Item> ordersAndBookings,
            Wallet wallet,
            List<CaseSummary> cases) {}

    Export of(String userId);
}
