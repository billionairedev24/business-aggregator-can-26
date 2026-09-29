package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Reference slice: {@code GET /api/v1/me/businesses}, {@code GET/PATCH /api/v1/merchants/{merchantId}}. */
@RecordApplicationEvents
class MerchantApiTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Nested
    class MyBusinesses {

        @Test
        void listsEveryBusinessTheCallerBelongsTo_withRole() throws Exception {
            var owner = data.user("Ravi Sandhu");
            var provider = data.merchant("provider", "Prairie Wrench");
            var seller = data.merchant("seller", "Prairie Wrench Parts");
            data.merchant("kitchen", "Someone else's kitchen");
            data.member(provider, owner, MerchantRole.OWNER);
            data.member(seller, owner, MerchantRole.BOOKKEEPER);

            mvc.perform(get("/api/v1/me/businesses").with(TestJwt.customer(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[*].displayName", contains("Prairie Wrench", "Prairie Wrench Parts")))
                    .andExpect(jsonPath("$.items[0].id").value(provider))
                    .andExpect(jsonPath("$.items[0].type").value("provider"))
                    .andExpect(jsonPath("$.items[0].tier").value("registered"))
                    .andExpect(jsonPath("$.items[0].city").value("Calgary"))
                    .andExpect(jsonPath("$.items[0].role").value("owner"))
                    .andExpect(jsonPath("$.items[1].type").value("seller"))
                    .andExpect(jsonPath("$.items[1].role").value("bookkeeper"));
        }

        @Test
        void emptyForUsersWithoutBusinesses() throws Exception {
            mvc.perform(get("/api/v1/me/businesses").with(TestJwt.customer(data.user("New user"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        void requiresAuthentication() throws Exception {
            mvc.perform(get("/api/v1/me/businesses")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Summary {

        @Test
        void anyMemberSeesTheHeaderSummary() throws Exception {
            var biz = data.business(MerchantRole.TECHNICIAN);

            mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(biz.merchantId()))
                    .andExpect(jsonPath("$.displayName").value("Prairie Wrench"))
                    .andExpect(jsonPath("$.type").value("provider"))
                    .andExpect(jsonPath("$.tier").value("registered"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.status").value("active"));
        }

        @Test
        void nonMemberIsForbidden() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var stranger = data.user("Stranger");

            mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.member(stranger)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"))
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        void memberWithoutMfaIsForbidden() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void tokenWithoutMerchantScopeIsForbidden() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.customer(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
    }

    @Nested
    class Rename {

        @Test
        void ownerRenames_andMerchantRenamedIsPublished() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(patchName(biz.merchantId(), "  Prairie Wrench & Sons  ").with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Prairie Wrench & Sons"));

            mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.displayName").value("Prairie Wrench & Sons"));
            assertThat(events.stream(MerchantRenamed.class)).singleElement().satisfies(e -> {
                assertThat(e.aggregateId()).isEqualTo(biz.merchantId());
                assertThat(e.actorId()).isEqualTo(biz.userId());
                assertThat(e.displayName()).isEqualTo("Prairie Wrench & Sons");
            });
        }

        @Test
        void unchangedNamePublishesNothing() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(patchName(biz.merchantId(), "Prairie Wrench").with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk());
            assertThat(events.stream(MerchantRenamed.class)).isEmpty();
        }

        @Test
        void technicianMayNotRename() throws Exception {
            var biz = data.business(MerchantRole.TECHNICIAN);

            mvc.perform(patchName(biz.merchantId(), "Hijacked").with(TestJwt.member(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        /** validation-rules.md › Business step › display_name — exact messages, one error per field. */
        @ParameterizedTest(name = "[{index}] ''{0}'' → {1}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "''     | required | Enter the name customers will see.",
                    "'   '  | required | Enter the name customers will see.",
                    "A      | length   | At least 2 characters.",
                    "' A '  | length   | At least 2 characters.", // passes @Size, caught by the DisplayName invariant
                })
        void invalidNamesAre422WithTheSpecMessage(String name, String rule, String message) throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(patchName(biz.merchantId(), name).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void tooLongNameIs422() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(patchName(biz.merchantId(), "x".repeat(81)).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("length"))
                    .andExpect(jsonPath("$.errors[0].message").value("At most 80 characters."));
        }

        @Test
        void missingFieldIs422Required() throws Exception {
            var biz = data.business(MerchantRole.OWNER);

            mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                    .andExpect(jsonPath("$.errors[0].rule").value("required"));
        }

        private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchName(
                String merchantId, String name) {
            var json = "{\"displayName\":\"%s\"}".formatted(name.replace("\"", "\\\""));
            return patch("/api/v1/merchants/{id}", merchantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json);
        }
    }
}
