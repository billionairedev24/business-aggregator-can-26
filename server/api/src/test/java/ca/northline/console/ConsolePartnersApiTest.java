package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-158: partner API clients registered in the console (admins: screen api, action keys) — register with a JWK Set
 * URL, scopes and the businesses asked; rotate; disable and enable; ask or remove businesses — and the owners' consent
 * in Settings › API (approve, decline, revoke). Audit rows, 403s for other roles and members, 422 messages, 409s.
 */
class ConsolePartnersApiTest extends IntegrationTest {

    static final String PARTNERS = "/api/v1/console/partners";
    static final String CONSENT = "/api/v1/merchants/{m}/settings/partners";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    String name() {
        return "acct-" + Ids.next().toLowerCase(Locale.ROOT).substring(16);
    }

    ResultActions register(String admin, String body) throws Exception {
        return mvc.perform(post(PARTNERS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(admin, StaffRole.ADMIN)));
    }

    String body(String name, String... merchants) {
        return """
                {"name":"%s","displayName":"Ledger Books","jwkSetUrl":"https://partner.example.invalid/jwks.json",
                 "scopes":["api.read"],"merchantIds":["%s"]}""".formatted(name, String.join("\",\"", merchants));
    }

    String id(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString())
                .path("id")
                .asString();
    }

    int audits(String action, String targetId) {
        return jdbc.sql("select count(*) from developer.audit_log where action = ? and target_id = ?")
                .params(action, targetId)
                .query(Integer.class)
                .single();
    }

    @Test
    void anAdminRegistersAPartner_andTheOwnerApproves() throws Exception {
        var admin = data.user("Admin");
        var merchant = data.merchant("seller", "Ledger Parts");
        var owner = data.user("Owner");
        data.member(merchant, owner, MerchantRole.OWNER);
        var name = name();

        var created = register(admin, body(name, merchant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("partner:" + name))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.tokenTtlSeconds").value(900))
                .andExpect(jsonPath("$.keyVersion").value(1))
                .andExpect(jsonPath("$.businesses[0].merchantId").value(merchant))
                .andExpect(jsonPath("$.businesses[0].businessName").value("Ledger Parts"))
                .andExpect(jsonPath("$.businesses[0].state").value("requested"));
        var partner = id(created);
        assertThat(audits("partner.registered", partner)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log
                        where action = 'partner.access_requested' and merchant_id = ? and target_id = ?""").params(merchant, partner).query(Integer.class).single())
                .isEqualTo(1);

        // the owner sees the request in Settings › API and approves it
        mvc.perform(get(CONSENT, merchant).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].partnerId").value(partner))
                .andExpect(jsonPath("$.items[0].displayName").value("Ledger Books"))
                .andExpect(jsonPath("$.items[0].scopes[0]").value("api.read"))
                .andExpect(jsonPath("$.items[0].state").value("requested"));
        decide(merchant, partner, "approve", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"));
        decide(merchant, partner, "approve", owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("partner_decided"));
        assertThat(jdbc.sql("select state from developer.partner_bindings where partner_id = ? and merchant_id = ?")
                        .params(partner, merchant)
                        .query(String.class)
                        .single())
                .isEqualTo("approved");

        // rotate (same URL: a new key version makes auth read the JWK Set again), then a new URL
        mvc.perform(post(PARTNERS + "/{id}/rotate", partner).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyVersion").value(2))
                .andExpect(jsonPath("$.rotatedAt").exists());
        mvc.perform(post(PARTNERS + "/{id}/rotate", partner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jwkSetUrl\":\"https://keys.partner.example.invalid/v2.json\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.keyVersion").value(3))
                .andExpect(jsonPath("$.jwkSetUrl").value("https://keys.partner.example.invalid/v2.json"));
        assertThat(audits("partner.keys_rotated", partner)).isEqualTo(2);

        // disable and enable
        mvc.perform(post(PARTNERS + "/{id}/disable", partner).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.state").value("disabled"));
        mvc.perform(get(CONSENT, merchant).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.items[0].partnerState").value("disabled"));
        mvc.perform(post(PARTNERS + "/{id}/enable", partner).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.state").value("active"));
        assertThat(audits("partner.disabled", partner)).isEqualTo(1);
        assertThat(audits("partner.enabled", partner)).isEqualTo(1);

        // the owner revokes; then nothing more to revoke
        decide(merchant, partner, "revoke", owner).andExpect(jsonPath("$.state").value("revoked"));
        decide(merchant, partner, "revoke", owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("partner_not_approved"))
                .andExpect(jsonPath("$.detail").value("Only approved access can be revoked."));

        // ask another business, then take it off
        var second = data.merchant("provider", "Ledger Repairs");
        mvc.perform(post(PARTNERS + "/{id}/businesses", partner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantIds\":[\"" + second + "\",\"" + merchant + "\"]}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businesses.length()").value(2))
                // a business already asked keeps its answer
                .andExpect(jsonPath("$.businesses[?(@.merchantId == '%s')].state".formatted(merchant))
                        .value("revoked"));
        mvc.perform(delete(PARTNERS + "/{id}/businesses/{m}", partner, second)
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.businesses.length()").value(1));

        mvc.perform(get(PARTNERS).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].name".formatted(partner))
                        .value(name));
    }

    @Test
    void ownersDecline_andOnlyOwnersAnswer() throws Exception {
        var admin = data.user("Admin");
        var merchant = data.merchant("seller", "Decline Parts");
        var owner = data.user("Owner");
        var technician = data.user("Technician");
        data.member(merchant, owner, MerchantRole.OWNER);
        data.member(merchant, technician, MerchantRole.TECHNICIAN);
        var partner = id(register(admin, body(name(), merchant)).andExpect(status().isCreated()));

        mvc.perform(get(CONSENT, merchant).with(TestJwt.member(technician))).andExpect(status().isOk());
        decide(merchant, partner, "approve", technician).andExpect(status().isForbidden());
        decide(merchant, partner, "approve", data.user("Outsider")).andExpect(status().isForbidden());
        mvc.perform(post(CONSENT + "/{p}/decision", merchant, partner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}")
                        .with(TestJwt.memberWithoutMfa(owner)))
                .andExpect(status().isForbidden());
        decide(merchant, partner, "maybe", owner)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve, decline or revoke."));
        decide(merchant, partner, "decline", owner)
                .andExpect(jsonPath("$.state").value("declined"));
        // a partner that never asked this business
        var other = data.merchant("seller", "Other Parts");
        var otherOwner = data.user("Owner");
        data.member(other, otherOwner, MerchantRole.OWNER);
        decide(other, partner, "approve", otherOwner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("partner_not_requested"));
    }

    ResultActions decide(String merchant, String partner, String decision, String user) throws Exception {
        return mvc.perform(post(CONSENT + "/{p}/decision", merchant, partner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\"}")
                .with(TestJwt.member(user)));
    }

    @Test
    void onlyAdminsRegister_andTheRulesAreValidated() throws Exception {
        var admin = data.user("Admin");
        var merchant = data.merchant("seller", "Rules Parts");
        mvc.perform(get(PARTNERS).with(TestJwt.staff(admin, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PARTNERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name(), merchant))
                        .with(TestJwt.staff(admin, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PARTNERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name(), merchant))
                        .with(TestJwt.staffWithoutMfa(admin, StaffRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get(PARTNERS).with(TestJwt.member(admin))).andExpect(status().isForbidden());

        register(admin, """
                        {"name":"Bad Name!","displayName":"","jwkSetUrl":"http://partner.example.invalid/jwks.json",
                         "scopes":["merchant"],"tokenTtlSeconds":5,"merchantIds":[]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message")
                        .value("Use 1 to 50 lower-case letters, digits or dashes, starting with a letter or a digit."))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message")
                        .value("Enter the partner's name, 1 to 80 characters."))
                .andExpect(jsonPath("$.errors[?(@.field == 'jwkSetUrl')].message")
                        .value("Enter the HTTPS address of the partner's JWK Set."))
                .andExpect(jsonPath("$.errors[?(@.field == 'scopes')].message")
                        .value("Choose api.read, api.write or both."))
                .andExpect(jsonPath("$.errors[?(@.field == 'tokenTtlSeconds')].message")
                        .value("Choose a token lifetime between 1 and 60 minutes."))
                .andExpect(jsonPath("$.errors[?(@.field == 'merchantIds')].message")
                        .value("Choose at least one business."));
        register(admin, body(name(), "01J00000000000000000000000"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("No business with this id."));
        var name = name();
        register(admin, body(name, merchant)).andExpect(status().isCreated());
        register(admin, body(name, merchant))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("partner_exists"));
        mvc.perform(post(PARTNERS + "/{id}/rotate", Ids.next()).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }
}
