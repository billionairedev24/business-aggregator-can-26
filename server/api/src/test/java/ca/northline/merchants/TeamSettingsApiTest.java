package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.TeamMembershipChanged;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Settings › Team &amp; roles and the invitee's accept flow. */
@RecordApplicationEvents
class TeamSettingsApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    private SettingsFixtures fx() {
        return new SettingsFixtures(jdbc);
    }

    private record Team(String merchantId, String owner, String tech) {}

    private Team team() {
        var owner = fx().person("Ravi Sandhu", SettingsFixtures.email("ravi"), null, "passkey");
        var tech = fx().person("Jas Gill", SettingsFixtures.email("jas"), null, "totp");
        var merchantId = data.merchant("provider", "Prairie Wrench");
        data.member(merchantId, owner, MerchantRole.OWNER);
        data.member(merchantId, tech, MerchantRole.TECHNICIAN);
        return new Team(merchantId, owner, tech);
    }

    private String invite(Team t, String json) throws Exception {
        var body = mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                        .with(TestJwt.member(t.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String url = JsonPath.read(body, "$.inviteUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Nested
    class Roster {

        @Test
        void listsMembersOwnerFirst_withSecondFactor_andTheRolesOnOffer() throws Exception {
            var t = team();
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(t.tech())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members[*].name", contains("Ravi Sandhu", "Jas Gill")))
                    .andExpect(jsonPath("$.members[0].role").value("owner"))
                    .andExpect(jsonPath("$.members[0].secondFactor").value("passkey"))
                    .andExpect(jsonPath("$.members[1].secondFactor").value("totp"))
                    .andExpect(jsonPath("$.members[1].you").value(true))
                    .andExpect(jsonPath("$.roles", contains("owner", "technician", "bookkeeper")));
        }

        @Test
        void kitchensOfferCooks() throws Exception {
            var owner = data.user("Owner");
            var kitchen = data.merchant("kitchen", "Pho Dau Bo");
            data.member(kitchen, owner, MerchantRole.OWNER);
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", kitchen).with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.roles", contains("owner", "cook", "bookkeeper")));
        }

        @Test
        void technicianCannotInviteOrChangeRoles() throws Exception {
            var t = team();
            mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                            .with(TestJwt.member(t.tech()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.ca\",\"role\":\"technician\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(delete("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.owner())
                            .with(TestJwt.member(t.tech())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void nonMemberAndMissingMfaAreForbidden() throws Exception {
            var t = team();
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.memberWithoutMfa(t.owner())))
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void ownerChangesARole_andRemovesAMember_whichIsAuditedAndPublished() throws Exception {
            var t = team();
            mvc.perform(patch("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.tech())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"bookkeeper\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("bookkeeper"));
            mvc.perform(delete("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.tech())
                            .with(TestJwt.member(t.owner())))
                    .andExpect(status().isNoContent());

            // Access is gone at once: membership is read on every request.
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(t.tech())))
                    .andExpect(status().isForbidden());
            assertThat(events.stream(TeamMembershipChanged.class)
                            .filter(e -> e.aggregateId().equals(t.merchantId()))
                            .map(TeamMembershipChanged::change))
                    .containsExactly("role_changed", "removed");
            assertThat(jdbc.sql("select action from developer.audit_log where merchant_id = ? order by at, id")
                            .params(t.merchantId())
                            .query(String.class)
                            .list())
                    .containsExactly("team.role_changed", "team.removed");
        }

        @Test
        void theLastOwnerStays() throws Exception {
            var t = team();
            mvc.perform(patch("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.owner())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"technician\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("last_owner"));
            mvc.perform(delete("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.owner())
                            .with(TestJwt.member(t.owner())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("last_owner"));
        }

        @Test
        void roleMustBeOnOffer() throws Exception {
            var t = team();
            mvc.perform(patch("/api/v1/merchants/{id}/settings/team/members/{u}", t.merchantId(), t.tech())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"cook\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("This role isn't available for this business."));
        }
    }

    @Nested
    class Invitations {

        @Test
        void ownerInvitesByEmail_sees_it_pending_andCanWithdrawIt() throws Exception {
            var t = team();
            var email = SettingsFixtures.email("sam");
            var body = mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email.toUpperCase(java.util.Locale.ROOT)
                                    + "\",\"role\":\"technician\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.invitation.email").value(email))
                    .andExpect(jsonPath("$.invitation.state").value("pending"))
                    .andExpect(jsonPath("$.inviteUrl", startsWith("http://localhost:3100/invite/")))
                    .andExpect(jsonPath("$.sent").value(true))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String id = JsonPath.read(body, "$.invitation.id");
            String url = JsonPath.read(body, "$.inviteUrl");
            assertThat(jdbc.sql("select token_hash from merchants.member_invitations where id = ?")
                            .params(id)
                            .query(String.class)
                            .single())
                    .hasSize(64)
                    .doesNotContain(url.substring(url.lastIndexOf('/') + 1));

            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(t.owner())))
                    .andExpect(jsonPath("$.invitations", hasSize(1)));
            mvc.perform(delete("/api/v1/merchants/{id}/settings/team/invitations/{i}", t.merchantId(), id)
                            .with(TestJwt.member(t.owner())))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(t.owner())))
                    .andExpect(jsonPath("$.invitations", hasSize(0)));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                nothing       | {"role":"technician"}                         | email | required | Enter an email or a mobile number.
                bad email     | {"email":"ravi@","role":"technician"}         | email | format   | That doesn't look like an email address.
                bad phone     | {"phone":"555","role":"technician"}           | phone | format   | Enter a valid Canadian mobile, e.g. +1 403 555 0148.
                no role       | {"email":"a@b.ca","role":""}                  | role  | required | Choose a role.
                cook          | {"email":"a@b.ca","role":"cook"}              | role  | allowed  | This role isn't available for this business.
                """)
        void validationMessages(String name, String json, String field, String rule, String message) throws Exception {
            var t = team();
            mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void oneOpenInvitationPerContact_andNoInvitesForMembers() throws Exception {
            var t = team();
            invite(t, "{\"phone\":\"+1 403 555 0199\",\"role\":\"bookkeeper\"}");
            mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"(403) 555-0199\",\"role\":\"technician\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("An invitation is already pending for this contact."));

            var jasEmail = jdbc.sql("select email::text from identity.users where id = ?")
                    .params(t.tech())
                    .query(String.class)
                    .single();
            mvc.perform(post("/api/v1/merchants/{id}/settings/team/invitations", t.merchantId())
                            .with(TestJwt.member(t.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + jasEmail + "\",\"role\":\"technician\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("This person is already on your team."));
        }

        @Test
        void inviteeWithTheirOwnLoginAcceptsAndJoins() throws Exception {
            var t = team();
            var email = SettingsFixtures.email("sam");
            var token = invite(t, "{\"email\":\"" + email + "\",\"role\":\"technician\"}");
            var sam = fx().person("Sam Lee", email, null, "passkey");

            mvc.perform(get("/api/v1/team-invitations/{t}", token).with(TestJwt.customer(sam)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.businessName").value("Prairie Wrench"))
                    .andExpect(jsonPath("$.role").value("technician"))
                    .andExpect(jsonPath("$.state").value("pending"))
                    .andExpect(jsonPath("$.forYou").value(true));
            mvc.perform(post("/api/v1/team-invitations/{t}/accept", token).with(TestJwt.member(sam)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.merchantId").value(t.merchantId()))
                    .andExpect(jsonPath("$.role").value("technician"));

            mvc.perform(get("/api/v1/merchants/{id}/settings/team", t.merchantId())
                            .with(TestJwt.member(sam)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(3)))
                    .andExpect(jsonPath("$.invitations", hasSize(0)));
            assertThat(events.stream(TeamMembershipChanged.class)
                            .filter(e -> e.aggregateId().equals(t.merchantId())
                                    && e.change().equals("joined")))
                    .singleElement()
                    .satisfies(e -> assertThat(e.userId()).isEqualTo(sam));

            mvc.perform(post("/api/v1/team-invitations/{t}/accept", token).with(TestJwt.member(sam)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("invitation_used"));
        }

        @Test
        void acceptNeedsASecondFactor_andTheInvitedContact() throws Exception {
            var t = team();
            var token = invite(t, "{\"email\":\"" + SettingsFixtures.email("sam") + "\",\"role\":\"technician\"}");
            var someoneElse = fx().person("Other", SettingsFixtures.email("other"), null, "passkey");
            mvc.perform(post("/api/v1/team-invitations/{t}/accept", token).with(TestJwt.customer(someoneElse)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
            mvc.perform(post("/api/v1/team-invitations/{t}/accept", token).with(TestJwt.member(someoneElse)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("invitation_not_for_you"));
        }

        @Test
        void expiredInvitationsCannotBeUsed() throws Exception {
            var t = team();
            var email = SettingsFixtures.email("sam");
            var token = invite(t, "{\"email\":\"" + email + "\",\"role\":\"technician\"}");
            jdbc.sql("update merchants.member_invitations set expires_at = now() - interval '1 minute', "
                            + "created_at = now() - interval '8 days' where merchant_id = ?")
                    .params(t.merchantId())
                    .update();
            var sam = fx().person("Sam Lee", email, null, "totp");
            mvc.perform(post("/api/v1/team-invitations/{t}/accept", token).with(TestJwt.member(sam)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("invitation_expired"));
            // …and a fresh one can be sent to the same person.
            invite(t, "{\"email\":\"" + email + "\",\"role\":\"technician\"}");
        }

        @Test
        void unknownTokenIs404() throws Exception {
            mvc.perform(get("/api/v1/team-invitations/{t}", "nope").with(TestJwt.customer(data.user("X"))))
                    .andExpect(status().isNotFound());
        }
    }
}
