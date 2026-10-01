package ca.northline.identity.api;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * What the consumer's account area needs from identity (S-59): the account menu's values, the language the person
 * reads receipts and notifications in, and their own data for "Download my data". The caller is always the person.
 */
public interface AccountFacts {

    /**
     * @param mfaPrimary {@code passkey | totp | sms} — how the person signs in ("Passkey")
     * @param addresses saved addresses
     * @param householdMembers people in the person's household, the person included (0 without one)
     * @param defaultProvince the default address's province, or null
     */
    record Facts(
            @Nullable String mfaPrimary,
            int addresses,
            int householdMembers,
            @Nullable String defaultProvince) {}

    Facts of(String userId);

    /** {@code en-CA | fr-CA} on {@code identity.users.locale} (receipts, notifications, the app's language). */
    void locale(String userId, String locale);

    /** The person's own profile as they gave it, for their data export. */
    record OwnProfile(
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String locale,
            LocalDate memberSince,
            @Nullable String pronouns,
            @Nullable String birthday) {}

    OwnProfile profile(String userId);
}
