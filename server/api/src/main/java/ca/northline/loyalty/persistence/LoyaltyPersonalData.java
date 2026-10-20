package ca.northline.loyalty.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, loyalty (S-152): what a person's completed sales earned, their invite code and referrals, and the Plus
 * membership they pay for. Erasure removes the invite code, the referrals they made or used and the wrong-code tries;
 * what sales earned and the membership are kept with the account id only (identity blanks the account): they are part
 * of the sales' and the subscription's financial records (the invoices are payments').
 */
@Component
@RequiredArgsConstructor
class LoyaltyPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "loyalty";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.<String, Object>of("u", subject.userId());
        return List.of(
                section(jdbc, "loyalty.earnings", "Points your orders and bookings earned", "Points gagnés", """
                        select kind, ref_type, ref_id, amount_cents, points, reward_points, reversed_points, expires_at,
                               created_at
                          from loyalty.earnings where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "loyalty.referrals", "Invite a neighbour", "Inviter un voisin", """
                        select c.code as your_code,
                               (select count(*) from loyalty.referrals r where r.referrer_id = :u) as neighbours_invited,
                               (select r.state from loyalty.referrals r where r.invitee_id = :u) as code_you_used_state
                          from (select :u as u) one left join loyalty.referral_codes c on c.user_id = one.u
                        """, p),
                section(
                        jdbc,
                        "loyalty.memberships",
                        "Northline Plus you pay for",
                        "Northline Plus que vous payez",
                        """
                        select plan, status, provider, current_period_end, cancel_at_period_end, started_at, canceled_at
                          from loyalty.memberships where payer_id = :u
                        """,
                        p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        jdbc.sql("delete from loyalty.referral_codes where user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from loyalty.referral_attempts where user_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from loyalty.referrals where invitee_id = :u or referrer_id = :u")
                .param("u", u)
                .update();
        var kept = jdbc.sql("""
                        select exists (select 1 from loyalty.earnings where customer_id = :u)
                            or exists (select 1 from loyalty.memberships where payer_id = :u)
                        """).param("u", u).query(Boolean.class).single();
        return kept ? Erasure.done().retaining("loyalty.earnings", Retention.TAX_RECORDS) : Erasure.done();
    }
}
