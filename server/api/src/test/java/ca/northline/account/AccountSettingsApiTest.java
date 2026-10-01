package ca.northline.account;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-59: the consumer's settings — profile, address book, household and Plus, saved cards (the fake gateway's
 * SetupIntents), billing history, notifications, language &amp; region, dietary &amp; accessibility, the account
 * menu's values and "Download my data". Always the caller's own data.
 */
class AccountSettingsApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String amara;
    String phone;

    @BeforeEach
    void person() {
        amara = data.user("Amara Osei");
        phone = "+1403" + (1_000_000 + Math.floorMod(amara.hashCode(), 8_999_999));
        jdbc.sql("update identity.users set email = ?, phone = ?, mfa_primary = 'passkey' where id = ?")
                .params("amara+" + amara + "@example.ca", phone, amara)
                .update();
    }

    @Nested
    class Profile {
        @Test
        void readAndUpdate() throws Exception {
            mvc.perform(get("/api/v1/me/profile").with(TestJwt.customer(amara)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value("Amara"))
                    .andExpect(jsonPath("$.lastName").value("Osei"))
                    .andExpect(jsonPath("$.phone").value(phone));
            mvc.perform(patch("/api/v1/me/profile")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"firstName":" Amara ","lastName":"Osei-Mensah","email":"amara.new+%s@example.ca",
                                     "pronouns":"she","birthday":"03/14"}""".formatted(amara)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value("Amara"))
                    .andExpect(jsonPath("$.lastName").value("Osei-Mensah"))
                    .andExpect(jsonPath("$.pronouns").value("she"))
                    .andExpect(jsonPath("$.birthday").value("03-14"));
        }

        @Test
        void validationMessages() throws Exception {
            var other = data.user("Someone Else");
            jdbc.sql("update identity.users set email = ? where id = ?")
                    .params("taken+" + other + "@example.ca", other)
                    .update();
            mvc.perform(patch("/api/v1/me/profile")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"nope\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='firstName')].message")
                            .value("First name is required."))
                    .andExpect(
                            jsonPath("$.errors[?(@.field=='lastName')].message").value("Last name is required."))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message")
                            .value("That doesn't look like an email address."));
            mvc.perform(patch("/api/v1/me/profile")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\":\"A\",\"lastName\":\"O\",\"email\":\"taken+%s@example.ca\"}"
                                    .formatted(other)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("That email is already used by another account."));
            mvc.perform(patch("/api/v1/me/profile")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"firstName\":\"A\",\"lastName\":\"O\",\"email\":\"a+%s@example.ca\",\"birthday\":\"14/45\"}"
                                            .formatted(amara)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("birthday"))
                    .andExpect(jsonPath("$.errors[0].message").value("Enter a birthday like 03/14 (month / day)."));
        }

        @Test
        void deleteAccountRequest_isRecordedOnce() throws Exception {
            var first = JsonPath.read(
                    mvc.perform(post("/api/v1/me/erasure-request").with(TestJwt.customer(amara)))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.erasureRequestedAt");
            mvc.perform(post("/api/v1/me/erasure-request").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.erasureRequestedAt").value(first));
        }
    }

    @Nested
    class Addresses {
        @Test
        void addEditDefaultRemove() throws Exception {
            var home = add("""
                    {"street":"1204 17 Ave SW","unit":"Apt 804","city":"Calgary","province":"ab","postal":"t2t0b8",
                     "note":"Buzz 0804 · leave at door"}""");
            var mum = add("""
                    {"label":"Mum","street":"44 Varsity Estates Cir NW","city":"Calgary","province":"AB","postal":"T3B 3B8"}""");
            mvc.perform(get("/api/v1/me/addresses").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].id").value(home))
                    .andExpect(jsonPath("$.items[0].isDefault").value(true))
                    .andExpect(jsonPath("$.items[0].postal").value("T2T 0B8"))
                    .andExpect(jsonPath("$.items[0].province").value("AB"))
                    .andExpect(jsonPath("$.items[1].label").value("Mum"));
            mvc.perform(patch("/api/v1/me/addresses/{id}", mum)
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"Mum's place\",\"note\":\"Side door\"}"))
                    .andExpect(jsonPath("$.label").value("Mum's place"))
                    .andExpect(jsonPath("$.note").value("Side door"));
            mvc.perform(post("/api/v1/me/addresses/{id}/default", mum).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items[0].id").value(mum))
                    .andExpect(jsonPath("$.items[1].isDefault").value(false));
            mvc.perform(delete("/api/v1/me/addresses/{id}", mum).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(home))
                    .andExpect(jsonPath("$.items[0].isDefault").value(true));
            var stranger = data.user("Stranger");
            mvc.perform(delete("/api/v1/me/addresses/{id}", home).with(TestJwt.customer(stranger)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void validationMessages() throws Exception {
            mvc.perform(post("/api/v1/me/addresses")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"street\":\"\",\"city\":\"\",\"province\":\"AB\",\"postal\":\"12345\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(
                            jsonPath("$.errors[?(@.field=='street')].message").value("Enter the street address."))
                    .andExpect(jsonPath("$.errors[?(@.field=='city')].message").value("Enter the city."))
                    .andExpect(jsonPath("$.errors[?(@.field=='postal')].message")
                            .value("Enter a Canadian postal code, like T2P 1B5."));
            mvc.perform(
                            post("/api/v1/me/addresses")
                                    .with(TestJwt.customer(amara))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"street\":\"1 Main St\",\"city\":\"X\",\"province\":\"ZZ\",\"postal\":\"T2P 1B5\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a Canadian province or territory."));
        }

        private String add(String body) throws Exception {
            return JsonPath.read(
                    mvc.perform(post("/api/v1/me/addresses")
                                    .with(TestJwt.customer(amara))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.id");
        }
    }

    @Test
    void plusTrial_startAndCancel() throws Exception {
        mvc.perform(get("/api/v1/me/household").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.plan").value("none"))
                .andExpect(jsonPath("$.members", hasSize(0)));
        mvc.perform(post("/api/v1/me/plus")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"monthly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("monthly"))
                .andExpect(jsonPath("$.renewsAt").exists())
                .andExpect(jsonPath("$.members[0].you").value(true))
                .andExpect(jsonPath("$.members[0].role").value("owner"));
        mvc.perform(post("/api/v1/me/plus")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"annual\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me/wallet").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.plus.plan").value("monthly"));
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.plan").value("none"));
        mvc.perform(get("/api/v1/me/wallet").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.plus").doesNotExist());
    }

    @Nested
    class PaymentMethods {
        @Test
        void saveCardsWithSetupIntents_defaultAndRemove() throws Exception {
            mvc.perform(get("/api/v1/me/payment-methods").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.provider").value("fake"))
                    .andExpect(jsonPath("$.items", hasSize(0)));
            var first = saveCard();
            var second = saveCard();
            mvc.perform(get("/api/v1/me/payment-methods").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[?(@.id=='%s')].isDefault".formatted(first))
                            .value(true))
                    .andExpect(jsonPath("$.items[?(@.id=='%s')].isDefault".formatted(second))
                            .value(false))
                    .andExpect(jsonPath("$.items[0].brand").value("visa"))
                    .andExpect(jsonPath("$.items[0].last4").value("4242"));
            mvc.perform(post("/api/v1/me/payment-methods/{id}/default", second).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items[?(@.id=='%s')].isDefault".formatted(second))
                            .value(true));
            mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.paymentMethod.brand").value("Visa"))
                    .andExpect(jsonPath("$.paymentMethod.last4").value("4242"));
            var stranger = data.user("Stranger");
            mvc.perform(delete("/api/v1/me/payment-methods/{id}", second).with(TestJwt.customer(stranger)))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/me/payment-methods/{id}", second).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(first))
                    .andExpect(jsonPath("$.items[0].isDefault").value(true));
        }

        @Test
        void someoneElsesSetupIntent_404() throws Exception {
            var setup = JsonPath.<String>read(
                    mvc.perform(post("/api/v1/me/payment-methods/setup-intents").with(TestJwt.customer(amara)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.setupIntentId");
            var stranger = data.user("Stranger");
            mvc.perform(post("/api/v1/me/payment-methods/setup-intents").with(TestJwt.customer(stranger)));
            mvc.perform(post("/api/v1/me/payment-methods")
                            .with(TestJwt.customer(stranger))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"setupIntentId\":\"%s\"}".formatted(setup)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void billingHistoryFromTheCallersEscrows() throws Exception {
            var shop = shopFixtures.shop("Calgary", "Glenmore Bakery", "master");
            var pi = Ids.next();
            jdbc.sql("""
                            insert into payments.payment_intents (id, stripe_pi, customer_id, amount_cents, currency,
                                   capture_method, state)
                            values (?, ?, ?, 3800, 'CAD', 'manual', 'authorized')""").params(pi, "pi_" + pi, amara).update();
            jdbc.sql("""
                            insert into payments.escrows (id, payment_intent_id, ref_type, ref_id, merchant_id, amount_cents,
                                   state, kind, label, order_number, customer_id, customer_name, tax_cents, occurred_at)
                            values (?, ?, 'order_line', ?, ?, 3620, 'held', 'goods', 'Grocery run', 'NL-48190', ?, 'A. Osei',
                                    180, now())""").params(Ids.next(), pi, Ids.next(), shop, amara).update();
            mvc.perform(get("/api/v1/me/billing-history").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].what").value("Grocery run"))
                    .andExpect(jsonPath("$.items[0].ref").value("NL-48190"))
                    .andExpect(jsonPath("$.items[0].amountCents").value(3800))
                    .andExpect(jsonPath("$.items[0].status").value("held"));
        }

        private String saveCard() throws Exception {
            var setup = JsonPath.<String>read(
                    mvc.perform(post("/api/v1/me/payment-methods/setup-intents").with(TestJwt.customer(amara)))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.clientSecret").exists())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.setupIntentId");
            var body = mvc.perform(post("/api/v1/me/payment-methods")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"setupIntentId\":\"%s\"}".formatted(setup)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            return ids.getFirst(); // newest first
        }
    }

    @Nested
    class Notifications {
        @Test
        void designDefaults_changes_securityLocked() throws Exception {
            mvc.perform(get("/api/v1/me/notifications").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath(
                            "$.events",
                            contains(
                                    "booking_reminders",
                                    "order_updates",
                                    "sign_off",
                                    "quotes_messages",
                                    "refunds_cases",
                                    "offers",
                                    "security")))
                    .andExpect(jsonPath("$.matrix.booking_reminders.sms").value(true))
                    .andExpect(jsonPath("$.matrix.booking_reminders.email").value(false))
                    .andExpect(jsonPath("$.quietOn").value(true))
                    .andExpect(jsonPath("$.quietFrom").value("22:00:00"))
                    .andExpect(jsonPath("$.quietTo").value("07:00:00"))
                    .andExpect(jsonPath("$.marketing").value("weekly"));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"matrix":{"offers":{"push":false}},"quietFrom":"23:00","quietTo":"06:00",
                                     "language":"fr","marketing":"none"}"""))
                    .andExpect(jsonPath("$.matrix.offers.push").value(false))
                    .andExpect(jsonPath("$.quietFrom").value("23:00:00"))
                    .andExpect(jsonPath("$.language").value("fr"))
                    .andExpect(jsonPath("$.marketing").value("none"));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"matrix\":{\"security\":{\"sms\":false}}}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Security alerts always go to every channel."));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quietFrom\":\"18:00\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("quietFrom"));
            mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.quietHours.from").value("11 pm"))
                    .andExpect(jsonPath("$.quietHours.to").value("6 am"));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quietOn\":false}"))
                    .andExpect(jsonPath("$.quietOn").value(false));
            mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.quietHours").doesNotExist());
        }
    }

    @Test
    void preferences_languageRegionDietary_andTheMenuValues() throws Exception {
        mvc.perform(get("/api/v1/me/preferences").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.units").value("metric"))
                .andExpect(jsonPath("$.dietary", hasSize(0)));
        mvc.perform(patch("/api/v1/me/preferences")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"fr","province":"bc","timeFormat":"24h","dietary":["vegan","halal"],
                                 "accessibility":["step_free"],"accessNotes":"Service dog on site","display":["reduce_motion"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.province").value("BC"))
                .andExpect(jsonPath("$.timeFormat").value("24h"))
                .andExpect(jsonPath("$.dietary", contains("halal", "vegan")))
                .andExpect(jsonPath("$.accessNotes").value("Service dog on site"));
        mvc.perform(get("/api/v1/me/profile").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.locale").value("fr-CA"));
        mvc.perform(get("/api/v1/me/account-summary")
                        .header("Accept-Language", "fr-CA")
                        .with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.dietary", contains("Halal", "Végane")))
                .andExpect(jsonPath("$.province").value("BC"))
                .andExpect(jsonPath("$.signIn").value("passkey"))
                .andExpect(jsonPath("$.addresses.count").value(0));
        mvc.perform(patch("/api/v1/me/preferences")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dietary\":[\"paleo\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("dietary"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose from the list."));
    }

    @Test
    void downloadMyData() throws Exception {
        mvc.perform(get("/api/v1/me/export").with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Disposition", startsWith("attachment; filename=\"northline-my-data.json\"")))
                .andExpect(jsonPath("$.profile.firstName").value("Amara"))
                .andExpect(jsonPath("$.addresses").isArray())
                .andExpect(jsonPath("$.ordersAndBookings").isArray())
                .andExpect(jsonPath("$.wallet.points.balance").value(0));
    }

    @Test
    void signedOut_401() throws Exception {
        for (var path : List.of(
                "/api/v1/me/profile",
                "/api/v1/me/addresses",
                "/api/v1/me/household",
                "/api/v1/me/payment-methods",
                "/api/v1/me/billing-history",
                "/api/v1/me/notifications",
                "/api/v1/me/preferences",
                "/api/v1/me/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
