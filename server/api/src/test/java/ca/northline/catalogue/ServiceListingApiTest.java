package ca.northline.catalogue;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestJwt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Service listings: {@code POST /services} (onboarding contract), the service editor save, validation, the table. */
class ServiceListingApiTest extends CatalogueApiTest {

    static final String SERVICES = "/api/v1/merchants/{m}/services";

    @Test
    void contractCreate_generatesSkuFromInitials_andListsWithMeta() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var body = """
                {"name":"Brake inspection","categoryId":"%s","pricingMode":"fixed","priceCents":8900,"durationMin":60,
                 "bufferMin":20,"included":"Pads, rotors, calipers","instantBook":true}
                """.formatted(MECHANIC);

        mvc.perform(postJson(SERVICES, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("service"))
                .andExpect(jsonPath("$.sku").value("SVC-BI"))
                .andExpect(jsonPath("$.vetting").value("draft"))
                .andExpect(jsonPath("$.completeness.percent").value(100));
        mvc.perform(postJson(SERVICES, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.sku").value("SVC-BI-2"));

        mvc.perform(get("/api/v1/merchants/{m}/listings?kind=service", biz.merchantId())
                        .header("Accept-Language", "fr-CA")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].meta").value("60 min · réservation instantanée"))
                .andExpect(jsonPath("$.items[0].stock").doesNotExist())
                .andExpect(jsonPath("$.items[0].pricingMode").value("fixed"));
    }

    @Test
    void quotePricedServiceHasNoPrice() throws Exception {
        var biz = provider(MerchantRole.TECHNICIAN);
        mvc.perform(postJson(SERVICES, """
                                {"name":"Engine rebuild","pricingMode":"quote","priceCents":50000,"durationMin":240,
                                 "bufferMin":0,"included":"Quoted after inspection","instantBook":false}
                                """, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents").doesNotExist())
                .andExpect(jsonPath("$.instantBook").value(false))
                .andExpect(jsonPath("$.completeness.missing[0].field").value("categoryId"));
    }

    @Test
    void updateKeepsTheSku_andBookkeeperCannotEdit() throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var id = json(mvc.perform(postJson(SERVICES, """
                                        {"name":"Oil & filter","pricingMode":"fixed","priceCents":7900,"durationMin":45}
                                        """, biz.merchantId()).with(TestJwt.member(biz.userId()))))
                .get("id")
                .asString();
        var update = """
                {"name":"Oil & filter · synthetic","pricingMode":"fixed","priceCents":8900,"durationMin":45,"sku":"SVC-OF"}
                """;
        mvc.perform(putJson(SERVICES + "/{id}", update, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Oil & filter · synthetic"))
                .andExpect(jsonPath("$.sku").value("SVC-OF"));
        var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
        mvc.perform(putJson(SERVICES + "/{id}", update, biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(postJson(SERVICES, update, biz.merchantId()).with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("name", "\"\"", "Enter a service name."),
                Arguments.of("name", "\"" + "N".repeat(81) + "\"", "At most 80 characters."),
                Arguments.of("pricingMode", "null", "Choose how you price this service."),
                Arguments.of("priceCents", "null", "Enter a price."),
                Arguments.of("priceCents", "-100", "Enter a price above $0."),
                Arguments.of("durationMin", "null", "Choose a duration."),
                Arguments.of("durationMin", "5", "Choose a duration between 15 minutes and 12 hours."),
                Arguments.of("bufferMin", "121", "Buffer must be between 0 and 120 minutes."),
                Arguments.of("included", "\"" + "i".repeat(2001) + "\"", "At most 2000 characters."),
                Arguments.of("categoryId", "\"shop.hardware-and-auto.auto-parts\"", "Choose a service category."),
                Arguments.of("categoryId", "\"service.automotive\"", "Choose a category down to the last level."),
                Arguments.of("sku", "\"" + "S".repeat(41) + "\"", "At most 40 characters."));
    }

    @ParameterizedTest(name = "[{index}] {0} → {2}")
    @MethodSource("invalidFields")
    void invalidFieldIs422WithItsMessage(String field, String jsonValue, String message) throws Exception {
        var biz = provider(MerchantRole.OWNER);
        var base = new java.util.LinkedHashMap<String, String>();
        base.put("name", "\"Diagnostic scan\"");
        base.put("pricingMode", "\"fixed\"");
        base.put("priceCents", "12000");
        base.put("durationMin", "60");
        base.put(field, jsonValue);
        var body = base.entrySet().stream()
                .map(e -> "\"%s\":%s".formatted(e.getKey(), e.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));

        mvc.perform(postJson(SERVICES, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == '%s')].message".formatted(field))
                        .value(message));
    }
}
