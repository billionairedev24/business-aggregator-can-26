package ca.northline.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Settings › Notifications: the member's own channel matrix. */
class NotificationMatrixApiTest extends IntegrationTest {

    @Test
    void startsWithTheDesignDefaults_andSavesChangedCells() throws Exception {
        var biz = data.business(MerchantRole.TECHNICIAN);
        mvc.perform(get("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.events",
                        Matchers.contains(
                                "new_booking",
                                "quote_request",
                                "customer_message",
                                "payout",
                                "dispute",
                                "low_stock",
                                "quality")))
                .andExpect(jsonPath("$.channels", Matchers.contains("push", "sms", "email")))
                .andExpect(jsonPath("$.matrix.new_booking.sms").value(true))
                .andExpect(jsonPath("$.matrix.new_booking.email").value(false))
                .andExpect(jsonPath("$.matrix.quality.push").value(false))
                .andExpect(jsonPath("$.quietFrom").value("21:00:00"));

        mvc.perform(put("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matrix\":{\"new_booking\":{\"email\":true},\"quality\":{\"push\":true}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrix.new_booking.email").value(true))
                .andExpect(jsonPath("$.matrix.new_booking.sms").value(true));
        mvc.perform(get("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.matrix.quality.push").value(true))
                .andExpect(jsonPath("$.matrix.dispute.sms").value(true));
    }

    @Test
    void unknownCellsAreRejected() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(put("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matrix\":{\"new_booking\":{\"fax\":true}}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick events and channels from the table."));
    }

    @Test
    void nonMemberAndMissingMfaAreForbidden() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.member(data.user("Stranger"))))
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/settings/notifications", biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }
}
