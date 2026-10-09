package ca.northline.promotions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.promotions.domain.Allocation;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Mobile gaps part 2: promo codes in the console (Finance screen) — finance and admins make them, others read or are
 * refused; every rule has its message (en, fr-CA); a code is unique; switching it off is audit-logged.
 */
class PromoCodesConsoleApiTest extends IntegrationTest {

    static final String CODES = "/api/v1/console/promotions/codes";

    @Autowired
    JdbcClient jdbc;

    ResultActions create(String staff, StaffRole role, String body) throws Exception {
        return mvc.perform(post(CODES)
                .with(TestJwt.staff(staff, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    static String window() {
        var now = Instant.now();
        return "\"startsAt\":\"%s\",\"endsAt\":\"%s\"".formatted(now, now.plus(Duration.ofDays(30)));
    }

    @Test
    void financeMakesAMerchantFundedCode_listsItAndSwitchesItOff() throws Exception {
        var fin = data.user("Fin Ance");
        var merchant = data.merchant("seller", "Glenmore Bakery");
        var code = "fall-" + Ids.next().substring(20).toLowerCase(java.util.Locale.ROOT);
        var body = create(fin, StaffRole.FINANCE, """
                        {"code":" %s ","kind":"amount","amountCents":500,"minSpendCents":2500,%s,"perCustomerLimit":2,
                         "totalLimit":100,"fundedBy":"merchant","merchantId":"%s","appliesTo":["goods","food"],
                         "description":"Fall launch"}""".formatted(code, window(), merchant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(jsonPath("$.merchantName").value("Glenmore Bakery"))
                .andExpect(jsonPath("$.appliesTo[0]").value("food"))
                .andExpect(jsonPath("$.state").value("live"))
                .andExpect(jsonPath("$.redeemed").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(body, "$.id");

        // unique
        create(fin, StaffRole.FINANCE, """
                        {"code":"%s","kind":"percent","percent":10,%s,"fundedBy":"northline","appliesTo":["service"]}""".formatted(code, window()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("code_taken"));

        // support reads nothing here (Finance screen); analysts neither; finance and admins list
        mvc.perform(get(CODES).with(TestJwt.staff(data.user("Sue"), StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(get(CODES).with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].description".formatted(id))
                        .value("Fall launch"));

        mvc.perform(patch(CODES + "/{id}", id)
                        .with(TestJwt.staff(fin, StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("off"));
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where target_id = ? and action like 'promotions.%'")
                        .params(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void onlyFinanceAndAdminsMakeCodes_withASecondFactor() throws Exception {
        var body = """
                {"code":"NOPE%s","kind":"percent","percent":10,%s,"fundedBy":"northline","appliesTo":["goods"]}""".formatted(Ids.next().substring(22), window());
        create(data.user("Tess"), StaffRole.TRUST_SAFETY, body).andExpect(status().isForbidden());
        create(data.user("Ana"), StaffRole.ANALYST, body).andExpect(status().isForbidden());
        mvc.perform(post(CODES)
                        .with(TestJwt.staffWithoutMfa(data.user("Fin"), StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(CODES)
                        .with(TestJwt.customer(data.user("Cus")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        create(data.user("Adam"), StaffRole.ADMIN, body).andExpect(status().isCreated());
    }

    @Test
    void everyRuleHasItsMessage_inEnglishAndFrench() throws Exception {
        var fin = data.user("Fin Ance");
        var now = Instant.now();
        create(fin, StaffRole.FINANCE, """
                        {"code":"x","kind":"percent","percent":150,"maxDiscountCents":10,"minSpendCents":-1,
                         "startsAt":"%s","endsAt":"%s","perCustomerLimit":0,"totalLimit":0,"fundedBy":"merchant",
                         "appliesTo":["pets"],"description":"%s"}""".formatted(now, now.minusSeconds(60), "d".repeat(201)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'code')].message")
                        .value("Use 3 to 20 letters, digits or dashes."))
                .andExpect(jsonPath("$.errors[?(@.field == 'percent')].message")
                        .value("Enter a percentage from 1 to 100."))
                .andExpect(jsonPath("$.errors[?(@.field == 'maxDiscountCents')].message")
                        .value("Enter a cap of at least $1.00, or none."))
                .andExpect(jsonPath("$.errors[?(@.field == 'minSpendCents')].message")
                        .value("Enter a minimum spend of $0 or more."))
                .andExpect(jsonPath("$.errors[?(@.field == 'endsAt')].message").value("End the code after it starts."))
                .andExpect(jsonPath("$.errors[?(@.field == 'perCustomerLimit')].message")
                        .value("Enter a limit of 1 or more."))
                .andExpect(jsonPath("$.errors[?(@.field == 'merchantId')].message")
                        .value("Choose the business that funds this code."))
                .andExpect(jsonPath("$.errors[?(@.field == 'appliesTo')].message")
                        .value("Choose at least one of shop, food or services."))
                .andExpect(jsonPath("$.errors[?(@.field == 'description')].message")
                        .value("At most 200 characters."));
        mvc.perform(post(CODES)
                        .with(TestJwt.staff(fin, StaffRole.FINANCE))
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"GOOD-ONE\",\"kind\":\"amount\",\"amountCents\":50,\"fundedBy\":\"x\","
                                + "\"appliesTo\":[\"goods\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'amountCents')].message")
                        .value("Entrez un montant d’au moins 1,00 $."))
                .andExpect(jsonPath("$.errors[?(@.field == 'endsAt')].message")
                        .value("Choisissez quand le code commence et se termine."))
                .andExpect(jsonPath("$.errors[?(@.field == 'fundedBy')].message")
                        .value("Choisissez qui finance le code."));
    }

    @Test
    void allocationSpreadsInProportion_keepsEachCap_andNeverLosesACent() {
        assertThat(Allocation.spread(1000, List.of(2000L, 3000L), List.of(1900L, 2900L)))
                .containsExactly(400L, 600L);
        assertThat(Allocation.spread(1001, List.of(1L, 1L), List.of(5000L, 5000L)))
                .containsExactly(501L, 500L);
        // a capped line passes its share on
        assertThat(Allocation.spread(1000, List.of(100L, 900L), List.of(0L, 2000L)))
                .containsExactly(0L, 1000L);
        // never more than the caps allow
        assertThat(Allocation.spread(5000, List.of(1000L, 1000L), List.of(900L, 900L)))
                .containsExactly(900L, 900L);
        assertThat(Allocation.spread(0, List.of(1000L), List.of(900L))).containsExactly(0L);
    }
}
