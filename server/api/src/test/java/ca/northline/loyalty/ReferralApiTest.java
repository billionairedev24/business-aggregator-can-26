package ca.northline.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.PaymentsFixture;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-152 Invite a neighbour: a code per person, a new neighbour uses it, both get 1,000 points (the default rule) after
 * the invitee's first completed order of at least $20 — with the abuse limits: not your own, new accounts only, once,
 * not within a household, a few wrong codes an hour, at most 5 rewarded referrals per referrer in 30 days.
 */
@Import(PaymentsFixture.class)
class ReferralApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    PaymentsFixture fx;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    JdbcClient jdbc;

    String newUser(String name) {
        var id = data.user(name);
        jdbc.sql("update identity.users set created_at = now() where id = ?")
                .params(id)
                .update();
        return id;
    }

    String code(String user) throws Exception {
        var body = mvc.perform(get("/api/v1/me/referral").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(body).get("code").asString();
    }

    ResultActions claim(String user, String code) throws Exception {
        return mvc.perform(post("/api/v1/me/referral/claim")
                .with(TestJwt.customer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    void completedOrder(String merchantId, String customer, long amount) {
        var refId = Ids.next();
        escrows.hold(new EscrowLifecycle.Hold(
                merchantId,
                EscrowKind.SERVICE,
                "booking",
                refId,
                amount,
                0,
                customer,
                "N. Neighbour",
                "Gutter cleaning",
                null,
                null,
                "Gutter cleaning",
                "search",
                "pi_" + Ids.next(),
                Instant.now(),
                null,
                null));
        escrows.confirmed("booking", refId, Instant.now());
    }

    long referralPoints(String user) {
        return jdbc.sql(
                        "select coalesce(sum(delta), 0) from trust.points_ledger where user_id = ? and ref_type = 'referral'")
                .params(user)
                .query(Long.class)
                .single();
    }

    @Test
    void everyoneHasACode_andALinkToShareIt() throws Exception {
        var user = newUser("Amara");
        var code = code(user);
        assertThat(code).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(code(user)).as("the same code every time").isEqualTo(code);
        mvc.perform(get("/api/v1/me/referral").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.shareUrl").value("http://localhost:3000/?invite=" + code))
                .andExpect(jsonPath("$.pointsEach").value(1000))
                .andExpect(jsonPath("$.minOrderCents").value(2000))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.canClaim").value(true));
    }

    @Test
    void bothSidesGetPoints_afterTheInviteesFirstCompletedOrder() throws Exception {
        var shop = fx.shop("provider", "master");
        var referrer = newUser("Referrer");
        var invitee = newUser("Invitee");
        claim(invitee, code(referrer).toLowerCase(java.util.Locale.ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed.state").value("pending"))
                .andExpect(jsonPath("$.canClaim").value(false));
        mvc.perform(get("/api/v1/me/referral").with(TestJwt.customer(referrer)))
                .andExpect(jsonPath("$.pending").value(1));

        completedOrder(shop.merchantId(), invitee, 1_500); // under the $20 minimum: still pending
        Thread.sleep(500);
        assertThat(referralPoints(invitee)).isZero();

        completedOrder(shop.merchantId(), invitee, 4_000);
        await().atMost(Duration.ofSeconds(10)).until(() -> referralPoints(invitee) == 1_000);
        assertThat(referralPoints(referrer)).isEqualTo(1_000);
        mvc.perform(get("/api/v1/me/referral").with(TestJwt.customer(referrer)))
                .andExpect(jsonPath("$.rewarded").value(1))
                .andExpect(jsonPath("$.pointsEarned").value(1_000));
        mvc.perform(get("/api/v1/me/referral").with(TestJwt.customer(invitee)))
                .andExpect(jsonPath("$.claimed.state").value("rewarded"));

        // another completed order pays nothing more
        completedOrder(shop.merchantId(), invitee, 4_000);
        Thread.sleep(500);
        assertThat(referralPoints(referrer)).isEqualTo(1_000);
    }

    @Test
    void theAbuseLimits() throws Exception {
        var referrer = newUser("Owner of the code");
        var code = code(referrer);
        claim(referrer, code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("That's your own invite code: share it with a neighbour instead."));
        claim(newUser("Blank"), " ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter an invite code."));

        var old = data.user("Old account");
        jdbc.sql("update identity.users set created_at = now() - interval '200 days' where id = ?")
                .params(old)
                .update();
        claim(old, code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Invite codes are for new neighbours: your account isn't new any more."));

        var partner = newUser("Same household");
        var household = Ids.next();
        jdbc.sql("insert into identity.households (id, name, plus_plan) values (?, 'Home', 'none')")
                .params(household)
                .update();
        jdbc.sql(
                        "insert into identity.household_members (household_id, user_id, role) values (?, ?, 'owner'), (?, ?, 'member')")
                .params(household, referrer, household, partner)
                .update();
        claim(partner, code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[0].message").value("Invite codes are for other households, not your own."));

        var once = newUser("Once");
        claim(once, code).andExpect(status().isOk());
        claim(once, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("referral_claimed"));

        var guesser = newUser("Guesser");
        for (var i = 0; i < 5; i++) {
            claim(guesser, "WRONG" + i)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("We don't know that invite code. Check it with the neighbour who sent it."));
        }
        claim(guesser, code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Too many tries. Try again in an hour."));
    }

    @Test
    void aReferrerOverTheMonthlyCap_isRefused() throws Exception {
        var shop = fx.shop("provider", "master");
        var referrer = newUser("Busy referrer");
        var code = code(referrer);
        // five already rewarded in the last 30 days
        for (var i = 0; i < 5; i++) {
            jdbc.sql("""
                            insert into loyalty.referrals (invitee_id, referrer_id, code, state, claimed_at, decided_at, points)
                            values (?, ?, ?, 'rewarded', now() - interval '5 days', now() - interval '1 day', 1000)""").params(Ids.next(), referrer, code).update();
        }
        var invitee = newUser("Sixth");
        claim(invitee, code).andExpect(status().isOk());
        completedOrder(shop.merchantId(), invitee, 5_000);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> "refused"
                        .equals(jdbc.sql("select state from loyalty.referrals where invitee_id = ?")
                                .params(invitee)
                                .query(String.class)
                                .single()));
        assertThat(jdbc.sql("select refused_reason from loyalty.referrals where invitee_id = ?")
                        .params(invitee)
                        .query(String.class)
                        .single())
                .isEqualTo("referrer_cap");
        assertThat(referralPoints(invitee)).isZero();
    }

    @Test
    void needsASignIn() throws Exception {
        mvc.perform(get("/api/v1/me/referral")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/referral/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
