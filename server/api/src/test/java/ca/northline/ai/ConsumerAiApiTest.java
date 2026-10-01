package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-132 end to end with the scripted model: natural-language search → the search API's filters (public, guest budget),
 * and help triage → a suggested case category and a staff summary (signed-in customers; opens nothing).
 */
class ConsumerAiApiTest extends ScriptedModelTest {

    @Autowired
    JdbcClient jdbc;

    @Nested
    class Search {

        @Test
        void aGuestGetsCheckedFiltersAndNothingInvented() throws Exception {
            MODEL.enqueue(MockOpenRouter.answer("""
                    {"q":"pho","kinds":["food","spaceship"],"maxPriceDollars":20,"openNow":true,"dietary":["halal","keto"],
                     "tiers":["platinum"],"sort":"distance","radiusKm":3,"explanation":"Halal pho under $20, open now."}"""));
            var guest = Ids.next();
            mvc.perform(post("/api/v1/search/interpret")
                            .header("X-Northline-Guest", guest)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"cheap halal pho open now near me\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.q").value("pho"))
                    .andExpect(jsonPath("$.kind.length()").value(1))
                    .andExpect(jsonPath("$.kind[0]").value("food"))
                    .andExpect(jsonPath("$.maxPrice").value(2000))
                    .andExpect(jsonPath("$.openNow").value(true))
                    .andExpect(jsonPath("$.dietary[0]").value("halal"))
                    .andExpect(jsonPath("$.dietary.length()").value(1))
                    .andExpect(jsonPath("$.tier.length()").value(0))
                    .andExpect(jsonPath("$.sort").doesNotExist())
                    .andExpect(jsonPath("$.radiusKm").doesNotExist())
                    .andExpect(jsonPath("$.aiAssisted").value(true));
            assertThat(MODEL.lastRequest().path("model").asString()).isEqualTo("google/gemini-3.5-flash-lite");
            assertThat(MODEL.lastRequest()
                            .path("messages")
                            .path(0)
                            .path("content")
                            .asString())
                    .contains("did NOT share a location");
            assertThat(jdbc.sql(
                                    "select person_id from ai.usage where feature = 'search_filters' order by created_at desc limit 1")
                            .query(String.class)
                            .single())
                    .startsWith("visitor:")
                    .doesNotContain(guest);
        }

        @Test
        void withALocationDistanceIsKept() throws Exception {
            MODEL.enqueue(
                    MockOpenRouter.answer(
                            "{\"q\":\"plumber\",\"kinds\":[\"service\"],\"sort\":\"distance\",\"radiusKm\":5,\"explanation\":\"Plumbers nearby.\"}"));
            mvc.perform(post("/api/v1/search/interpret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"closest plumber within 5 km\",\"hasLocation\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sort").value("distance"))
                    .andExpect(jsonPath("$.radiusKm").value(5.0));
        }

        @Test
        void validation() throws Exception {
            mvc.perform(post("/api/v1/search/interpret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"   \"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Type what you're looking for."));
            mvc.perform(post("/api/v1/search/interpret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + "x".repeat(201) + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Keep it under 200 characters."));
            assertThat(MODEL.requests).isEmpty();
        }
    }

    @Nested
    class Triage {

        @Test
        void suggestsACategoryAndRouteAndOpensNothing() throws Exception {
            var customer = Ids.next();
            MODEL.enqueue(
                    MockOpenRouter.answer(
                            "{\"category\":\"billing\",\"urgent\":false,\"summary\":\"The customer reports being charged twice.\"}"));
            mvc.perform(
                            post("/api/v1/me/help/triage")
                                    .with(TestJwt.customer(customer))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"text\":\"I was charged twice, call me at 403-555-0199\",\"refType\":\"order\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category").value("billing"))
                    .andExpect(jsonPath("$.route").value("dispute"))
                    .andExpect(jsonPath("$.urgent").value(false))
                    .andExpect(jsonPath("$.summary").value("The customer reports being charged twice."))
                    .andExpect(jsonPath("$.aiAssisted").value(true));
            assertThat(MODEL.lastRequest()
                            .path("messages")
                            .path(1)
                            .path("content")
                            .asString())
                    .contains("charged twice")
                    .contains("[PHONE]")
                    .doesNotContain("555-0199");
            assertThat(casesOf(customer)).as("triage opens no case").isZero();
        }

        @Test
        void safetyIsAlwaysUrgentAndAnUnknownCategoryIsOther() throws Exception {
            MODEL.enqueue(
                    MockOpenRouter.answer("{\"category\":\"safety\",\"urgent\":false,\"summary\":\"Got sick.\"}"));
            mvc.perform(post("/api/v1/me/help/triage")
                            .with(TestJwt.customer(Ids.next()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"We got sick after the shrimp.\"}"))
                    .andExpect(jsonPath("$.urgent").value(true))
                    .andExpect(jsonPath("$.route").value("support"));
            MODEL.enqueue(MockOpenRouter.answer("{\"category\":\"refund_now\",\"summary\":\"Wants money back.\"}"));
            mvc.perform(post("/api/v1/me/help/triage")
                            .with(TestJwt.customer(Ids.next()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"I just want my money back now.\"}"))
                    .andExpect(jsonPath("$.category").value("other"))
                    .andExpect(jsonPath("$.route").value("support"));
        }

        @Test
        void needsASignInAndEnoughText() throws Exception {
            mvc.perform(post("/api/v1/me/help/triage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Something is wrong with my order\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/me/help/triage")
                            .with(TestJwt.customer(Ids.next()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"bad\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(
                            jsonPath("$.errors[0].message").value("Tell us a little more (at least 10 characters)."));
            mvc.perform(post("/api/v1/me/help/triage")
                            .with(TestJwt.customer(Ids.next()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Something is wrong here\",\"refType\":\"quote\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose order or booking."));
            assertThat(MODEL.requests).isEmpty();
        }

        long casesOf(String customer) {
            return jdbc.sql("select count(*) from messaging.threads where counterpart_id = ?")
                    .param(customer)
                    .query(Long.class)
                    .single();
        }
    }
}
