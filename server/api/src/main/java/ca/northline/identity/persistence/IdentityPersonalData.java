package ca.northline.identity.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, identity: the account, its addresses, household, sign-ins, passkeys, console roles and on-call shifts. Runs
 * {@link #LAST}: other modules find rows by the contact this module blanks.
 *
 * <p>Erasure: the account row stays as a pseudonym (orders, payments and reviews point at its id) with every personal
 * field blanked and status {@code erased}; sign-ins are ended and stripped of device, IP and city; passkeys, console
 * roles, future on-call shifts and household memberships go. Addresses keep only city, province and the postal code's
 * first three characters (the place of supply on tax records); street, unit, notes and the map point go.
 */
@Component
@RequiredArgsConstructor
class IdentityPersonalData implements PersonalDataContributor {

    static final String PHONE = "phone";
    static final String PHONE_FORMAT = "Enter a phone number like +1 403 555 0123.";
    static final String PHONE_TAKEN = "That mobile number is already used by another account.";
    private static final Pattern E164 = Pattern.compile("\\+1[2-9][0-9]{9}");

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public int order() {
        return LAST;
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "identity.account", "Account", "Compte", """
                        select id, first_name, last_name, display_name, email::text as email, phone, phone_verified_at,
                               locale, pronouns, birthday_month, birthday_day, reliability_score, mfa_primary, status,
                               terms_version, terms_accepted_at, created_at, erasure_requested_at
                          from identity.users where id = :u
                        """, p),
                section(jdbc, "identity.addresses", "Addresses", "Adresses", """
                        select id, label, street, unit, city, province, postal, access_note, is_default, created_at,
                               deleted_at
                          from identity.addresses where user_id = :u order by created_at
                        """, p),
                section(jdbc, "identity.household", "Household", "Ménage", """
                        select h.id, h.name, m.role, h.plus_plan, h.plus_since, h.renews_at
                          from identity.household_members m join identity.households h on h.id = m.household_id
                         where m.user_id = :u
                        """, p),
                section(jdbc, "identity.signIns", "Sign-ins", "Connexions", """
                        select id, created_at, last_seen_at, method, acr, device, city, host(ip) as ip, revoked_at,
                               revoke_reason
                          from identity.sessions where user_id = :u order by created_at desc
                        """, p),
                section(jdbc, "identity.passkeys", "Passkeys", "Clés d'accès", """
                        select id, device_label, last_used_at from identity.passkeys where user_id = :u
                        """, p),
                section(jdbc, "identity.consoleRoles", "Northline staff roles", "Rôles du personnel Northline", """
                        select role, granted_at from identity.platform_roles where user_id = :u
                        """, p),
                section(jdbc, "identity.onCall", "On-call shifts", "Gardes", """
                        select id, starts_at, ends_at, duty from identity.oncall_shifts where user_id = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var u = subject.userId();
        jdbc.sql("""
                        update identity.users
                           set first_name = null, last_name = null, display_name = null, email = null, phone = null,
                               phone_verified_at = null, pronouns = null, birthday_month = null, birthday_day = null,
                               reliability_score = null, status = 'erased', erased_at = coalesce(erased_at, :now),
                               updated_at = now()
                         where id = :u
                        """).param("u", u).param("now", now).update();
        var addresses = jdbc.sql("""
                        update identity.addresses
                           set street = '', unit = null, access_note = null, label = null, geom = null, place_id = null,
                               postal = left(replace(coalesce(postal, ''), ' ', ''), 3), is_default = false,
                               deleted_at = coalesce(deleted_at, :now)
                         where user_id = :u
                        """).param("u", u).param("now", now).update();
        jdbc.sql("""
                        update identity.sessions
                           set revoked_at = coalesce(revoked_at, :now), revoke_reason = coalesce(revoke_reason, 'erased'),
                               device = null, ip = null, city = null
                         where user_id = :u
                        """).param("u", u).param("now", now).update();
        jdbc.sql("delete from identity.passkeys where user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from identity.platform_roles where user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from identity.oncall_shifts where user_id = :u and starts_at > :now")
                .param("u", u)
                .param("now", now)
                .update();
        var households = jdbc.sql("delete from identity.household_members where user_id = :u returning household_id")
                .param("u", u)
                .query((rs, _) -> rs.getString(1))
                .list();
        if (!households.isEmpty()) {
            jdbc.sql("""
                            update identity.households h set name = null
                             where h.id in (:ids)
                               and not exists (select 1 from identity.household_members m where m.household_id = h.id)
                            """).param("ids", households).update();
        }
        var outcome = Erasure.done().retaining("identity.account", Retention.FINANCIAL_RECORDS);
        return addresses > 0 ? outcome.retaining("identity.addresses.placeOfSupply", Retention.TAX_RECORDS) : outcome;
    }

    @Override
    public Set<String> correctable() {
        return Set.of(PHONE);
    }

    @Override
    public void correct(Subject subject, String field, String value) {
        if (!PHONE.equals(field)) {
            throw new IllegalArgumentException("Not correctable here: " + field);
        }
        var phone = value.replaceAll("[\\s().-]", "");
        if (!E164.matcher(phone).matches()) {
            throw RuleViolation.of(PHONE, "format", PHONE_FORMAT);
        }
        var taken = jdbc.sql("select exists (select 1 from identity.users where phone = :p and id <> :u)")
                .param("p", phone)
                .param("u", subject.userId())
                .query(Boolean.class)
                .single();
        if (taken) {
            throw RuleViolation.of(PHONE, "unique", PHONE_TAKEN);
        }
        jdbc.sql("""
                        update identity.users set phone = :p, phone_verified_at = :now, updated_at = now()
                         where id = :u
                        """)
                .param("p", phone)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("u", subject.userId())
                .update();
    }
}
