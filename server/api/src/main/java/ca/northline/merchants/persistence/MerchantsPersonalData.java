package ca.northline.merchants.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, merchants: a team member's or owner's memberships, the invitations sent to their email or mobile, and their
 * records as a principal (owner, director …) of a business, with the identity checks of those.
 *
 * <p>Erasure: memberships and invitations go. The only owner of a business that is open (active, paused, pending or
 * suspended) holds the erasure: ownership moves to someone else, or the business closes, first. A principal's legal
 * name and identity checks stay the business's know-your-customer records, unlinked from the account and without the
 * email the check was sent to.
 */
@Component
@RequiredArgsConstructor
class MerchantsPersonalData implements PersonalDataContributor {

    static final String LEGAL_NAME = "legalName";
    static final String LEGAL_NAME_LENGTH = "Enter the full legal name.";
    static final String OPEN_BUSINESS = "('active', 'paused', 'pending', 'suspended')";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "merchants";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = params(subject);
        return List.of(
                section(jdbc, "merchants.memberships", "Business teams", "Équipes d'entreprise", """
                        select mm.merchant_id, m.display_name as business, mm.role, mm.bookable, mm.joined_at
                          from merchants.merchant_members mm join merchants.merchants m on m.id = mm.merchant_id
                         where mm.user_id = :u
                        """, p),
                section(jdbc, "merchants.invitations", "Team invitations", "Invitations d'équipe", """
                        select id, merchant_id, role, email::text as email, phone, created_at, expires_at, accepted_at,
                               revoked_at
                          from merchants.member_invitations
                         where accepted_by = :u or (cast(:email as text) is not null and email = cast(:email as citext))
                            or (cast(:phone as text) is not null and phone = :phone)
                        """, p),
                section(jdbc, "merchants.principal", "Business principal records", "Dossiers de dirigeant", """
                        select p.id, p.merchant_id, p.legal_name, p.role, p.ownership_pct,
                               (select json_agg(json_build_object('status', c.status, 'nameMatch', c.name_match,
                                       'dobMatch', c.dob_match, 'verifiedAt', c.verified_at))
                                  from merchants.owner_identity_checks c where c.principal_id = p.id) as identity_checks
                          from merchants.merchant_principals p where p.user_id = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var p = params(subject);
        var soleOwned = jdbc.sql("""
                        select mm.merchant_id from merchants.merchant_members mm
                          join merchants.merchants m on m.id = mm.merchant_id
                         where mm.user_id = :u and mm.role = 'owner' and m.status in %s
                           and not exists (select 1 from merchants.merchant_members o
                                            where o.merchant_id = mm.merchant_id and o.role = 'owner' and o.user_id <> :u)
                        """.formatted(OPEN_BUSINESS))
                .params(p)
                .query((rs, _) -> rs.getString(1))
                .list();
        var p2 = new HashMap<String, @Nullable Object>(p);
        p2.put("keep", soleOwned.isEmpty() ? List.of("") : soleOwned);
        var left = Set.copyOf(
                jdbc.sql("""
                        delete from merchants.merchant_members
                         where user_id = :u and merchant_id not in (:keep)
                        returning merchant_id
                        """).params(p2).query((rs, _) -> rs.getString(1)).list());
        jdbc.sql("""
                        delete from merchants.member_invitations
                         where accepted_by = :u or (cast(:email as text) is not null and email = cast(:email as citext))
                            or (cast(:phone as text) is not null and phone = :phone)
                        """).params(p).update();
        var principals = jdbc.sql("""
                        update merchants.owner_identity_checks c set email = null, updated_at = now()
                          from merchants.merchant_principals pr
                         where pr.id = c.principal_id and pr.user_id = :u and c.email is not null
                        """).params(p).update();
        principals += jdbc.sql("update merchants.merchant_principals set user_id = null where user_id = :u")
                .params(p)
                .update();
        var outcome = Erasure.done().touching(left);
        if (principals > 0) {
            outcome = outcome.retaining("merchants.principals", Retention.KYC_RECORDS);
        }
        return soleOwned.isEmpty() ? outcome : outcome.holding("merchants.ownership", Hold.BUSINESS_OWNER);
    }

    @Override
    public Set<String> correctable() {
        return Set.of(LEGAL_NAME);
    }

    @Override
    public void correct(Subject subject, String field, String value) {
        if (!LEGAL_NAME.equals(field)) {
            throw new IllegalArgumentException("Not correctable here: " + field);
        }
        var name = value.strip();
        if (name.length() < 2 || name.length() > 120) {
            throw RuleViolation.of(LEGAL_NAME, "length", LEGAL_NAME_LENGTH);
        }
        jdbc.sql("update merchants.merchant_principals set legal_name = :name where user_id = :u")
                .param("name", name)
                .param("u", subject.userId())
                .update();
    }

    private static Map<String, @Nullable Object> params(Subject subject) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("u", subject.userId());
        p.put("email", subject.email());
        p.put("phone", subject.phone());
        return p;
    }
}
