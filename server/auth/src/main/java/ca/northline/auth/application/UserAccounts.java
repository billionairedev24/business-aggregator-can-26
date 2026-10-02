package ca.northline.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code identity.users} (+ memberships and platform roles for token claims). */
public interface UserAccounts {

    Optional<UserAccount> findById(String id);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByPhone(String e164);

    boolean emailInUse(String email);

    boolean phoneInUse(String e164);

    void create(NewAccount account);

    /** Merchant ids from {@code merchants.merchant_members} (token {@code merchants} claim). */
    List<String> merchantIds(String userId);

    /** Platform roles from {@code identity.platform_roles} (token {@code roles} claim). */
    List<String> platformRoles(String userId);

    record NewAccount(
            String id,
            String firstName,
            String lastName,
            String phone,
            String email,
            String locale,
            String mfaPrimary,
            String termsVersion,
            Instant at,
            @Nullable String termsLanguage,
            @Nullable Instant termsEnglishRequestedAt) {

        /** Without the Terms' language (accounts created outside the registration form). */
        public NewAccount(
                String id,
                String firstName,
                String lastName,
                String phone,
                String email,
                String locale,
                String mfaPrimary,
                String termsVersion,
                Instant at) {
            this(id, firstName, lastName, phone, email, locale, mfaPrimary, termsVersion, at, null, null);
        }
    }
}
