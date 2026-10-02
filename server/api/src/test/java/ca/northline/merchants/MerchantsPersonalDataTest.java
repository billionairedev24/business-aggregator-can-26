package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-105: merchants' part — memberships and invitations go; the only owner of an open business holds the erasure; a
 * principal's legal name and identity checks stay the business's KYC records, unlinked.
 */
class MerchantsPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "merchants";
    }

    @Test
    void aTeamMemberLeavesTheTeamTheirInvitationsGoAndTheirPrincipalRecordIsUnlinked() {
        var person = person();
        var u = person.userId();
        var merchant = data.merchant("provider", "Prairie Wrench");
        data.member(merchant, data.user("Owner"), MerchantRole.OWNER);
        data.member(merchant, u, MerchantRole.TECHNICIAN);
        jdbc.sql("""
                        insert into merchants.member_invitations (id, merchant_id, role, email, token_hash, invited_by,
                                                                  expires_at)
                        values (:id, :m, 'technician', :email, :hash, 'someone', now() + interval '7 days')
                        """)
                .param("id", Ids.next())
                .param("m", merchant)
                .param("email", person.email())
                .param("hash", "h-" + Ids.next())
                .update();
        var principal = Ids.next();
        jdbc.sql("""
                        insert into merchants.merchant_principals (id, merchant_id, legal_name, role, user_id)
                        values (:id, :m, 'Dana Kowalski', 'owner', :u)
                        """).param("id", principal).param("m", merchant).param("u", u).update();
        jdbc.sql("""
                        insert into merchants.owner_identity_checks (id, merchant_id, principal_id, status, delivery,
                                                                     email, requested_by)
                        values (:id, :m, :p, 'verified', 'email', :email, 'someone')
                        """)
                .param("id", Ids.next())
                .param("m", merchant)
                .param("p", principal)
                .param("email", person.email())
                .update();

        assertThat(records(person, "merchants.memberships")).isEqualTo(1);
        assertThat(records(person, "merchants.invitations")).isEqualTo(1);
        assertThat(section(person, "merchants.principal").orElseThrow().json()).contains("verified");

        var outcome = erase(person);

        var p = Map.of("u", u, "m", merchant, "p", principal);
        assertThat(count("select count(*) from merchants.merchant_members where user_id = :u", p))
                .isZero();
        assertThat(count("select count(*) from merchants.member_invitations where merchant_id = :m", p))
                .isZero();
        assertThat(text(
                        "select coalesce(user_id, '-') || legal_name from merchants.merchant_principals where id = :p",
                        p))
                .isEqualTo("-Dana Kowalski");
        assertThat(text("select email::text from merchants.owner_identity_checks where principal_id = :p", p))
                .isNull();
        assertThat(outcome.merchantIds()).containsExactly(merchant);
        assertThat(outcome.retained()).containsExactly(new Kept<>("merchants.principals", Retention.KYC_RECORDS));
        assertThat(outcome.held()).isEmpty();
        assertThat(erase(person).held()).isEmpty();
    }

    @Test
    void theOnlyOwnerOfAnOpenBusinessHoldsTheErasure() {
        var person = person();
        var merchant = data.merchant("provider", "Prairie Wrench");
        data.member(merchant, person.userId(), MerchantRole.OWNER);

        var outcome = erase(person);

        assertThat(outcome.held()).containsExactly(new Kept<>("merchants.ownership", Hold.BUSINESS_OWNER));
        assertThat(count(
                        "select count(*) from merchants.merchant_members where user_id = :u",
                        Map.of("u", person.userId())))
                .isEqualTo(1);

        data.member(merchant, data.user("Co-owner"), MerchantRole.OWNER);
        assertThat(erase(person).held()).isEmpty();
    }
}
