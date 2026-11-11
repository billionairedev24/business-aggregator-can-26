package ca.northline.tools.demo;

import ca.northline.platform.AesKeyRing;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * The demo's sign-in accounts (plan.json {@code accounts}) and the background people (owners of the other businesses,
 * reviewers). A sign-in account gets the password and authenticator key handed to the seed — {@code
 * DEMO_ACCOUNT_<KEY>_PASSWORD} and {@code DEMO_ACCOUNT_<KEY>_TOTP} (base32), from the Kubernetes Secret {@code
 * northline-demo-accounts} or the local git-ignored file; the seed never makes, prints or logs them. The password is
 * hashed as northline-auth hashes (Argon2id, {@code {argon2}} prefix), the key sealed with {@code TOTP_KEY} as
 * northline-auth seals it (AES-256-GCM, {@code key_id} = {@code TOTP_KEY_ID}). Background people have neither and can't
 * sign in.
 */
final class DemoAccounts {

    /** Email domain of every demo person (a reserved test domain: nothing is ever delivered there). */
    static final String DOMAIN = "demo.northline.test";

    private static final String TERMS_VERSION = "3.0";

    private final Db db;
    private final DemoPlan plan;
    private final Map<String, String> env;
    private final Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final Map<String, String> ids = new LinkedHashMap<>();

    DemoAccounts(Db db, DemoPlan plan, Map<String, String> env) {
        this.db = db;
        this.plan = plan;
        this.env = env;
    }

    static String userId(String key) {
        return DemoIds.of("user:" + key);
    }

    static String variable(String accountKey, String what) {
        return "DEMO_ACCOUNT_" + accountKey.toUpperCase(Locale.ROOT) + "_" + what;
    }

    /** The account keys whose password or key is missing from the environment. */
    java.util.List<String> missingCredentials() {
        return plan.accounts().keySet().stream()
                .sorted()
                .filter(k -> blank(env.get(variable(k, "PASSWORD"))) || blank(env.get(variable(k, "TOTP"))))
                .toList();
    }

    void seed(DemoMarket market, Instant now) {
        var keyRing = AesKeyRing.of(
                "TOTP_KEY", env.getOrDefault("TOTP_KEY_ID", "v1"), required("TOTP_KEY"), env.get("TOTP_PREVIOUS_KEYS"));
        for (var e : plan.accounts().entrySet()) {
            var key = e.getKey();
            var a = e.getValue();
            var id = userId(key);
            upsertUser(id, a.email(), a.first(), a.last(), now, true);
            db.update("""
                    insert into auth.passwords (user_id, hash, changed_at) values (?, ?, ?)
                    on conflict (user_id) do update set hash = excluded.hash, changed_at = excluded.changed_at
                    """, id, "{argon2}" + argon2.encode(required(variable(key, "PASSWORD"))), now);
            var secret = required(variable(key, "TOTP")).replace(" ", "").toUpperCase(Locale.ROOT);
            db.update("""
                    insert into auth.totp_secrets (user_id, secret_enc, key_id, confirmed_at, last_used_step) values (?, ?, ?, ?, null)
                    on conflict (user_id) do update set secret_enc = excluded.secret_enc, key_id = excluded.key_id,
                      confirmed_at = excluded.confirmed_at, last_used_step = null
                    """, id, keyRing.encrypt(secret), keyRing.currentId(), now);
            db.update("delete from identity.platform_roles where user_id = ?", id);
            for (var role : a.platformRoles()) {
                db.update(
                        "insert into identity.platform_roles (user_id, role, granted_at) values (?, ?, ?) on conflict do nothing",
                        id,
                        role,
                        now);
            }
            if (a.address() != null) {
                address(id, key, a.address(), market);
            }
            ids.put(key, id);
        }
        for (int i = 0; i < plan.reviewers().size(); i++) {
            var name = plan.reviewers().get(i);
            var parts = name.split(" ", 2);
            upsertUser(
                    reviewerId(i),
                    "reviewer" + (i + 1) + "@people." + DOMAIN,
                    parts[0],
                    parts.length > 1 ? parts[1] : "",
                    now,
                    false);
        }
    }

    /** A background owner for a business that isn't the demo owner's. */
    String backgroundOwner(String businessKey, String businessName, Instant now) {
        var id = DemoIds.of("owner:" + businessKey);
        var first = OWNER_FIRST[Math.floorMod(businessKey.hashCode(), OWNER_FIRST.length)];
        var last = OWNER_LAST[Math.floorMod((businessKey + businessName).hashCode(), OWNER_LAST.length)];
        upsertUser(id, "owner." + businessKey + "@people." + DOMAIN, first, last, now, false);
        return id;
    }

    static String reviewerId(int i) {
        return DemoIds.of("reviewer:" + i);
    }

    String id(String accountKey) {
        var id = ids.get(accountKey);
        return id == null ? userId(accountKey) : id;
    }

    private void upsertUser(String id, String email, String first, String last, Instant now, boolean signsIn) {
        db.update(
                """
                insert into identity.users (id, email, display_name, first_name, last_name, locale, mfa_primary, status,
                                            email_verified_at, terms_version, terms_accepted_at, terms_language, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'en-CA', ?, 'active', ?, ?, ?, 'en', ?, ?)
                on conflict (id) do update set email = excluded.email, display_name = excluded.display_name,
                  first_name = excluded.first_name, last_name = excluded.last_name, mfa_primary = excluded.mfa_primary,
                  status = 'active', email_verified_at = coalesce(identity.users.email_verified_at, excluded.email_verified_at),
                  terms_version = excluded.terms_version, updated_at = excluded.updated_at
                """,
                id,
                email,
                (first + " " + last).strip(),
                first,
                last,
                signsIn ? "totp" : null,
                now,
                TERMS_VERSION,
                now,
                now.minusSeconds(86_400L * 120),
                now);
    }

    private void address(String userId, String key, DemoPlan.Address address, DemoMarket market) {
        var p = market.pointFor("address:" + key);
        var zone = market.deliveryZoneAt(db, p[0], p[1]).orElse(null);
        db.update(
                """
                insert into identity.addresses (id, user_id, street, unit, city, province, geom, zone_id, is_default, label, created_at)
                values (?, ?, ?, ?, ?, ?, ST_GeogFromText(?), ?, true, ?, now())
                on conflict (id) do update set street = excluded.street, unit = excluded.unit, city = excluded.city,
                  province = excluded.province, geom = excluded.geom, zone_id = excluded.zone_id, deleted_at = null
                """,
                addressId(key),
                userId,
                address.street(),
                address.unit(),
                market.city(),
                market.province(),
                DemoMarket.point(p[0], p[1]),
                zone,
                address.label());
    }

    static String addressId(String accountKey) {
        return DemoIds.of("address:" + accountKey);
    }

    private String required(String name) {
        var v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v.strip();
    }

    private static boolean blank(@Nullable String v) {
        return v == null || v.isBlank();
    }

    private static final String[] OWNER_FIRST = {
        "Sam", "Noor", "Elena", "Marcus", "Wei", "Fatima", "Jonah", "Amélie", "Ravi", "Grace", "Tariq", "Lucía", "Owen",
        "Hana", "Pierre", "Zara", "Ben", "Ingrid", "Kwame", "Rosa"
    };
    private static final String[] OWNER_LAST = {
        "Tremblay", "Nguyen", "Okafor", "Singh", "MacLeod", "Haddad", "Kowalski", "Gagnon", "Chen", "Bergstrom",
        "Mensah", "Roy", "Patel", "Leblanc", "Kim", "Morrison", "Ibrahim", "Côté", "Larsen", "Diaz"
    };
}
