package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.application.TeamInvitationIssued;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** S-13: Settings › Team invitations are emailed after the invite commits; the copy-link fallback stays. */
class TeamInvitationEmailTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    private record Team(String merchantId, String owner) {}

    private Team team(String ownerLocale) {
        var fx = new SettingsFixtures(jdbc);
        var owner = fx.person("Ravi Sandhu", SettingsFixtures.email("ravi"), null, "passkey");
        jdbc.sql("update identity.users set locale = ? where id = ?")
                .params(ownerLocale, owner)
                .update();
        var merchantId = data.merchant("provider", "Prairie Wrench");
        data.member(merchantId, owner, MerchantRole.OWNER);
        return new Team(merchantId, owner);
    }

    private String invite(Team team, String json) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", team.merchantId())
                        .with(TestJwt.member(team.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void anEmailInvitation_isEmailedOnce_inTheInvitersLanguage_withTheSameLink() throws Exception {
        var team = team("fr-CA");
        var invitee = SettingsFixtures.email("sam");

        var body = invite(team, "{\"email\":\"" + invitee + "\",\"role\":\"technician\"}");

        assertThat((Boolean) JsonPath.read(body, "$.sent")).isTrue();
        String link = JsonPath.read(body, "$.inviteUrl");
        await().atMost(Duration.ofSeconds(10)).until(() -> !emails.to(invitee).isEmpty());
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(emails.to(invitee)).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Ravi Sandhu vous invite à rejoindre Prairie Wrench sur Northline");
            assertThat(mail.text()).contains(link, "en tant que Technicien");
            assertThat(mail.html()).contains("href=\"" + link + "\"");
            assertThat(mail.headers()).doesNotContainKey("List-Unsubscribe"); // transactional
            assertThat(mail.tag()).isEqualTo("team-invitation");
        });
    }

    @Test
    void aMobileInvitation_isTextedOnce_inTheInvitersLanguage_andTheLinkIsStillShown() throws Exception {
        var team = team("fr-CA");
        var phone = "+140355501" + String.format("%02d", Math.floorMod(System.nanoTime(), 100));

        var body = mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", team.merchantId())
                        .with(TestJwt.member(team.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"role\":\"bookkeeper\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.inviteUrl").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String link = JsonPath.read(body, "$.inviteUrl");

        await().atMost(Duration.ofSeconds(10)).until(() -> texts.to(phone).size() == 1);
        assertThat(texts.to(phone).getFirst().body())
                .startsWith("Northline : ")
                .contains("comme comptable", link)
                .endsWith(link);
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .until(() -> texts.to(phone).size() == 1);
    }

    @Test
    void aWithdrawnInvitation_isNeverEmailed_evenWhenItsEventIsDeliveredLate() throws Exception {
        var team = team("en-CA");
        var invitee = SettingsFixtures.email("late");
        var body = invite(team, "{\"email\":\"" + invitee + "\",\"role\":\"technician\"}");
        String id = JsonPath.read(body, "$.invitation.id");
        await().atMost(Duration.ofSeconds(10)).until(() -> emails.to(invitee).size() == 1);
        mvc.perform(delete("/api/v1/merchants/{m}/settings/team/invitations/{i}", team.merchantId(), id)
                        .with(TestJwt.member(team.owner())))
                .andExpect(status().is2xxSuccessful());

        // A second delivery of the "issued" event (a new event id, so no de-duplication) after the withdrawal.
        tx.executeWithoutResult(_ -> publisher.publishEvent(new TeamInvitationIssued(
                Ids.next(), Instant.now(), id, team.merchantId(), team.owner(), "token-of-a-withdrawn-invitation")));
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).until(() -> true);

        assertThat(emails.to(invitee)).hasSize(1);
    }
}
