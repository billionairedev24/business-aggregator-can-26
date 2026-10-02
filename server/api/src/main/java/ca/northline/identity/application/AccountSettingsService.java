package ca.northline.identity.application;

import ca.northline.identity.application.AccountSettings.AccountSettingsStore;
import ca.northline.identity.application.AccountSettings.Address;
import ca.northline.identity.application.AccountSettings.AddressChange;
import ca.northline.identity.application.AccountSettings.Household;
import ca.northline.identity.application.AccountSettings.ManageAddresses;
import ca.northline.identity.application.AccountSettings.ManageHousehold;
import ca.northline.identity.application.AccountSettings.ManageProfile;
import ca.northline.identity.application.AccountSettings.NewAddress;
import ca.northline.identity.application.AccountSettings.Profile;
import ca.northline.identity.application.AccountSettings.ProfileChange;
import ca.northline.identity.domain.AccountRules;
import ca.northline.region.api.ProvinceProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile, address book, household and Plus of the signed-in consumer (S-59). */
@Service
@RequiredArgsConstructor
@Transactional
class AccountSettingsService implements ManageProfile, ManageAddresses, ManageHousehold {

    /** Design 06: "Start 30-day free trial". */
    static final Duration TRIAL = Duration.ofDays(30);

    private final AccountSettingsStore store;
    private final Regions regions;
    private final Clock clock;

    // ── Profile ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Profile profile(String userId) {
        return store.profile(userId).orElseThrow(() -> new NotFound("user", userId));
    }

    @Override
    public Profile update(String userId, ProfileChange change) {
        var email = change.email().strip();
        if (!store.emailFree(userId, email)) {
            throw RuleViolation.of("email", "unique", AccountRules.EMAIL_TAKEN);
        }
        var pronouns = change.pronouns() == null || change.pronouns().isBlank() ? null : change.pronouns();
        if (pronouns != null && !AccountRules.PRONOUN_CODES.contains(pronouns)) {
            throw RuleViolation.of("pronouns", "allowed", AccountRules.PRONOUNS);
        }
        store.updateProfile(
                userId,
                new ProfileChange(
                        change.firstName().strip(), change.lastName().strip(), email, pronouns, change.birthday()),
                clock.instant());
        return profile(userId);
    }

    @Override
    public void locale(String userId, String locale) {
        store.locale(userId, locale.toLowerCase(Locale.ROOT).startsWith("fr") ? "fr-CA" : "en-CA", clock.instant());
    }

    // ── Addresses ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Address> list(String userId) {
        return store.addresses(userId);
    }

    @Override
    public Address add(String userId, NewAddress a) {
        var clean = new NewAddress(
                blankToNull(a.label()),
                a.street().strip(),
                blankToNull(a.unit()),
                a.city().strip(),
                AccountRules.province(a.province(), provinceCodes()),
                AccountRules.postal(a.postal()),
                blankToNull(a.note()));
        return store.insertAddress(userId, clean, store.addresses(userId).isEmpty());
    }

    @Override
    public Address change(String userId, String addressId, AddressChange change) {
        requireAddress(userId, addressId);
        store.changeAddress(
                userId,
                addressId,
                new AddressChange(blankToNull(change.label()), blankToNull(change.unit()), blankToNull(change.note())));
        return requireAddress(userId, addressId);
    }

    @Override
    public List<Address> makeDefault(String userId, String addressId) {
        requireAddress(userId, addressId);
        store.makeDefault(userId, addressId);
        return store.addresses(userId);
    }

    @Override
    public List<Address> remove(String userId, String addressId) {
        var address = requireAddress(userId, addressId);
        store.removeAddress(userId, addressId, clock.instant());
        var rest = store.addresses(userId);
        if (address.isDefault() && !rest.isEmpty()) {
            store.makeDefault(userId, rest.getFirst().id());
            return store.addresses(userId);
        }
        return rest;
    }

    private Set<String> provinceCodes() {
        return regions.provinces().stream().map(ProvinceProfile::code).collect(Collectors.toSet());
    }

    private Address requireAddress(String userId, String addressId) {
        return store.addresses(userId).stream()
                .filter(a -> a.id().equals(addressId))
                .findFirst()
                .orElseThrow(() -> new NotFound("address", addressId));
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ── Household & Plus ──────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Household household(String userId) {
        return store.household(userId);
    }

    @Override
    public Household startPlus(String userId, String plan) {
        if (!"monthly".equals(plan) && !"annual".equals(plan)) {
            throw RuleViolation.of("plan", "allowed", "Choose monthly or annual.");
        }
        var current = store.household(userId);
        if (!"none".equals(current.plan())) {
            throw new Conflict("plus_active", "Northline Plus is already active for your household.");
        }
        var now = clock.instant();
        store.plus(store.ensureHousehold(userId), plan, now, now.plus(TRIAL));
        return store.household(userId);
    }

    @Override
    public Household cancelPlus(String userId) {
        var current = store.household(userId);
        if (current.id() != null && !"none".equals(current.plan())) {
            store.plus(current.id(), "none", null, null);
        }
        return store.household(userId);
    }
}
