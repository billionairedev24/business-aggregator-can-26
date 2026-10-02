package ca.northline.identity.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The consumer's own account settings (S-59, design 06 account: profile, addresses &amp; household, Northline Plus,
 * delete account): inbound ports and the outbound store over {@code identity.users}, {@code addresses} and
 * {@code households}.
 */
public final class AccountSettings {
    private AccountSettings() {}

    /**
     * @param pronouns {@code she | he | they | none}, or null when never chosen
     * @param erasureRequestedAt when the person's verified erasure request (S-105, privacy module) was made, else null
     */
    public record Profile(
            String id,
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            String locale,
            LocalDate memberSince,
            @Nullable String pronouns,
            @Nullable MonthDay birthday,
            @Nullable BigDecimal reliability,
            @Nullable Instant erasureRequestedAt) {}

    public record ProfileChange(
            String firstName,
            String lastName,
            String email,
            @Nullable String pronouns,
            @Nullable MonthDay birthday) {}

    /** @param label the person's own name for it ("Mum", "Work"), or null */
    public record Address(
            String id,
            @Nullable String label,
            String street,
            @Nullable String unit,
            String city,
            String province,
            String postal,
            @Nullable String note,
            boolean isDefault) {}

    public record NewAddress(
            @Nullable String label,
            String street,
            @Nullable String unit,
            String city,
            String province,
            String postal,
            @Nullable String note) {}

    /** What can change on a saved address without moving it (moving = a new address). */
    public record AddressChange(
            @Nullable String label,
            @Nullable String unit,
            @Nullable String note) {}

    /** @param role {@code owner | member}; {@code you} = the caller */
    public record Member(String userId, String name, String role, boolean you) {}

    /** @param plan {@code none | monthly | annual} */
    public record Household(
            @Nullable String id,
            List<Member> members,
            String plan,
            @Nullable Instant plusSince,
            @Nullable Instant renewsAt) {}

    public interface ManageProfile {
        Profile profile(String userId);

        /** 422 per field; 422 {@code email} when another account has it. */
        Profile update(String userId, ProfileChange change);

        /** {@code en-CA | fr-CA} — the language of receipts and notifications (the Language tab). */
        void locale(String userId, String locale);
    }

    public interface ManageAddresses {
        List<Address> list(String userId);

        Address add(String userId, NewAddress address);

        Address change(String userId, String addressId, AddressChange change);

        List<Address> makeDefault(String userId, String addressId);

        /** Removes it from the book (orders that used it keep their copy); the next one becomes the default. */
        List<Address> remove(String userId, String addressId);
    }

    public interface ManageHousehold {
        Household household(String userId);

        /**
         * "Start 30-day free trial": the person's household (created when they have none) gets the plan with the
         * trial's end as its renewal. No billing exists yet (DECISIONS S-59).
         */
        Household startPlus(String userId, String plan);

        Household cancelPlus(String userId);
    }

    /** Outbound port. */
    public interface AccountSettingsStore {
        Optional<Profile> profile(String userId);

        /** False when another account has the email. */
        boolean emailFree(String userId, String email);

        void updateProfile(String userId, ProfileChange change, Instant at);

        void locale(String userId, String locale, Instant at);

        List<Address> addresses(String userId);

        Address insertAddress(String userId, NewAddress address, boolean isDefault);

        void changeAddress(String userId, String addressId, AddressChange change);

        void makeDefault(String userId, String addressId);

        void removeAddress(String userId, String addressId, Instant at);

        Household household(String userId);

        /** The person's household id, creating one (they own it) when they have none. */
        String ensureHousehold(String userId);

        void plus(String householdId, String plan, @Nullable Instant since, @Nullable Instant renewsAt);
    }
}
