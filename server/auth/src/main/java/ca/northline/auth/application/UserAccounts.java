package ca.northline.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code identity.users} (+ memberships and platform roles for token claims). */
public interface UserAccounts {

    Optional<UserAccount> findById(String id);

    Optional<UserAccount> findByEmail(String email);

    /** The account whose mobile is this confirmed number (an unconfirmed one finds nothing). */
    Optional<UserAccount> findByPhone(String e164);

    boolean emailInUse(String email);

    /** A confirmed mobile (S-154: an email account's unconfirmed one doesn't count, see {@link #anyPhone}). */
    boolean phoneInUse(String e164);

    /** S-154: any account has this mobile, confirmed or not. */
    boolean anyPhone(String e164);

    void create(NewAccount account);

    /**
     * S-154: an account whose email was confirmed by a code or a link (email + password; no second factor yet, so
     * {@code mfa_primary} is empty). The optional mobile is stored unconfirmed.
     */
    void createWithEmail(EmailAccount account);

    /** S-154: the person proved they read this address (a reset code or link); keeps the first time. */
    void emailVerified(String userId, Instant at);

    /** S-154: frees a number held unconfirmed by another account (someone just confirmed it by SMS). */
    void releaseUnverifiedPhone(String e164);

    /** Merchant ids from {@code merchants.merchant_members} (token {@code merchants} claim). */
    List<String> merchantIds(String userId);

    /** Platform roles from {@code identity.platform_roles} (token {@code roles} claim). */
    List<String> platformRoles(String userId);

    /** S-154: see {@link #createWithEmail}. */
    record EmailAccount(
            String id,
            String firstName,
            String lastName,
            @Nullable String phone,
            String email,
            String locale,
            String termsVersion,
            Instant termsAcceptedAt,
            @Nullable String termsLanguage,
            @Nullable Instant termsEnglishRequestedAt,
            Instant at) {}

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
