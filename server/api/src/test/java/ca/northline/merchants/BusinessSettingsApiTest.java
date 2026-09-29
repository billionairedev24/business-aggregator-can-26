package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Settings › Business: {@code GET/PUT /api/v1/merchants/{id}/settings/business}. */
@RecordApplicationEvents
class BusinessSettingsApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    private static final String BODY = """
            {"displayName":"Prairie Wrench Mobile","legalName":"Prairie Wrench Automotive Ltd.",
             "gstNumber":"123456789 RT0001","serviceArea":"Calgary + 40 km","cancellationPolicy":"24h",
             "autoAcceptQuoteCents":15000,"languages":["en","pa"]}
            """;

    private String corp(MerchantRole role, String userId) {
        var merchantId = data.merchant("provider", "Prairie Wrench");
        jdbc.sql("update merchants.merchants set structure = 'corp_ab', gst_number = '784512369 RT0001' where id = ?")
                .params(merchantId)
                .update();
        data.member(merchantId, userId, role);
        return merchantId;
    }

    @Test
    void anyMemberReadsTheBusinessTab() throws Exception {
        var biz = data.business(MerchantRole.BOOKKEEPER);
        mvc.perform(get("/api/v1/merchants/{id}/settings/business", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Prairie Wrench"))
                .andExpect(jsonPath("$.legalName").value("Prairie Wrench (legal)"))
                .andExpect(jsonPath("$.cancellationPolicy").value("12h"))
                .andExpect(jsonPath("$.gstRequired").value(false))
                .andExpect(jsonPath("$.type").value("provider"));
    }

    @Test
    void ownerSaves_renamePublished_andChangedLegalFactsGoBackToReview() throws Exception {
        var owner = data.user("Ravi Sandhu");
        var merchantId = corp(MerchantRole.OWNER, owner);
        var fx = new SettingsFixtures(jdbc);
        var registry = fx.verification(merchantId, "registry", null, "2021456789", "verified", null, "registry");
        var gst = fx.verification(merchantId, "registry", "CRA", "784512369 RT0001", "verified", null, "gst");

        mvc.perform(put("/api/v1/merchants/{id}/settings/business", merchantId)
                        .with(TestJwt.member(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Prairie Wrench Mobile"))
                .andExpect(jsonPath("$.gstNumber").value("123456789 RT0001"))
                .andExpect(jsonPath("$.serviceArea").value("Calgary + 40 km"))
                .andExpect(jsonPath("$.cancellationPolicy").value("24h"))
                .andExpect(jsonPath("$.autoAcceptQuoteCents").value(15000))
                .andExpect(jsonPath("$.languages", contains("en", "pa")));

        assertThat(events.stream(MerchantRenamed.class)
                        .filter(e -> e.aggregateId().equals(merchantId)))
                .hasSize(1);
        assertThat(checkStatus(registry)).isEqualTo("submitted");
        assertThat(checkStatus(gst)).isEqualTo("submitted");
        assertThat(jdbc.sql("select profile ->> 'serviceArea' from merchants.merchants where id = ?")
                        .params(merchantId)
                        .query(String.class)
                        .single())
                .isEqualTo("Calgary + 40 km");
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where merchant_id = ? and action = 'business.updated'")
                        .params(merchantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void technicianCannotEdit() throws Exception {
        var biz = data.business(MerchantRole.TECHNICIAN);
        mvc.perform(put("/api/v1/merchants/{id}/settings/business", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
    }

    @Test
    void nonMemberAndMissingMfaAreForbidden() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/settings/business", biz.merchantId())
                        .with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/settings/business", biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @ParameterizedTest(name = "{0} = {1} → {3}")
    @CsvSource(delimiter = '|', textBlock = """
            displayName          | '""'                        | required | Enter the name customers will see.
            displayName          | '"P"'                       | length   | At least 2 characters.
            legalName            | '"  "'                      | required | Enter the registered legal name.
            gstNumber            | '"12345 RT1"'               | format   | Format is 9 digits + RT0001 (e.g. 123456789 RT0001).
            gstNumber            | '""'                        | required | Required for corporations, co-ops and non-profits.
            serviceArea          | '"LONG"'                    | length   | At most 200 characters.
            cancellationPolicy   | '"48h"'                     | format   | Choose one of the options.
            autoAcceptQuoteCents | -1                          | range    | Enter an amount of $0 or more.
            languages            | '[]'                        | required | Pick at least one language.
            languages            | '["English"]'               | format   | Pick languages from the list.
            """)
    void validationMessages(String field, String value, String rule, String message) throws Exception {
        var owner = data.user("Owner");
        var merchantId = corp(MerchantRole.OWNER, owner);
        var json = value.equals("\"LONG\"") ? "\"" + "x".repeat(201) + "\"" : value;
        var body =
                BODY.replaceFirst("\"" + field + "\":(\"[^\"]*\"|\\[[^\\]]*\\]|-?\\d+)", "\"" + field + "\":" + json);
        mvc.perform(put("/api/v1/merchants/{id}/settings/business", merchantId)
                        .with(TestJwt.member(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].rule").value(rule))
                .andExpect(jsonPath("$.errors[0].message").value(message));
    }

    @Test
    void cancellationPolicyCheckConstraintBacksTheRule() {
        var merchantId = data.merchant("provider", "Prairie Wrench");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.sql("update merchants.merchants set cancellation_policy = '48h' where id = ?")
                                .params(merchantId)
                                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String checkStatus(String verificationId) {
        return jdbc.sql("select status from merchants.verifications where id = ?")
                .params(verificationId)
                .query(String.class)
                .single();
    }
}
