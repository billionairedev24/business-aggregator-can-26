package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.privacy.application.PrivacyWork;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-159, deleting an account from the app or the site: an email + password account without a mobile confirms by a
 * code sent to its verified email (S-154 accounts may have no phone and no second factor), the consequences shown
 * before confirming, and the two confirmation emails (deletion scheduled, deletion done), en and fr-CA.
 */
class AccountDeletionApiTest extends IntegrationTest {

    static final String PATH = "/api/v1/me/privacy-requests";
    static final Pattern CODE = Pattern.compile("\\b([0-9]{6})\\b");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PrivacyWork work;

    String person;
    String email;

    @BeforeEach
    void emailAccount() {
        person = Ids.next();
        email = "nadia-" + person.toLowerCase(Locale.ROOT) + "@example.ca";
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, locale, status,
                               email_verified_at)
                        values (:id, 'Nadia', 'Roy', 'Nadia Roy', :email, 'fr-CA', 'active', now())
                        """).param("id", person).param("email", email).update();
    }

    private String openErasure() throws Exception {
        var body = mvc.perform(post(PATH)
                        .with(TestJwt.customer(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"erasure\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("awaiting_verification"))
                .andExpect(jsonPath("$.codeChannel").value("email"))
                .andExpect(jsonPath("$.codeSentTo").value("n•••@example.ca"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String lastCode() {
        var mail = emails.to(email).getLast();
        var matcher = CODE.matcher(mail.subject());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void anAccountWithoutAMobileConfirmsByEmail_andIsToldWhenDeletionStartsAndEnds() throws Exception {
        var id = openErasure();
        assertThat(emails.to(email)).singleElement().satisfies(mail -> {
            // in the language the request was made in (the site's); the deletion emails follow the profile's
            assertThat(mail.subject()).endsWith("is your code to confirm your request");
            assertThat(mail.text())
                    .contains("Confirm it’s you, Nadia")
                    .contains("/account/delete");
        });
        assertThat(texts.to(email)).isEmpty();

        mvc.perform(post(PATH + "/{id}/verify", id)
                        .with(TestJwt.customer(person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(lastCode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"))
                .andExpect(jsonPath("$.verification").value("code"))
                .andExpect(jsonPath("$.scheduledFor").isNotEmpty());
        assertThat(emails.to(email)).hasSize(2).last().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Nous allons supprimer votre compte Northline");
            assertThat(mail.text()).contains("La suppression commence le").contains("Annuler la suppression");
        });

        // after the grace period the pipeline runs; the completion email goes to the address the account had
        jdbc.sql("update privacy.requests set scheduled_for = now() - interval '1 minute' where id = :id")
                .param("id", id)
                .update();
        assertThat(work.run(id)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> emails.to(email).size() == 3);
        assertThat(emails.to(email).getLast().subject()).isEqualTo("Votre compte Northline a été supprimé");
        assertThat(jdbc.sql("select status from identity.users where id = :id")
                        .param("id", person)
                        .query(String.class)
                        .single())
                .isEqualTo("erased");
    }

    @Test
    void aNewCodeGoesByEmailToo_andAnAccountWithNoVerifiedContactIsSentToItsSecondFactor() throws Exception {
        var id = openErasure();
        jdbc.sql("update privacy.requests set code_expires_at = now() + interval '1 minute' where id = :id")
                .param("id", id)
                .update(); // as if the first code was sent 14 minutes ago (past the resend wait)
        mvc.perform(post(PATH + "/{id}/verification-code", id).with(TestJwt.customer(person)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeChannel").value("email"));
        assertThat(emails.to(email)).hasSize(2);

        var unverified = Ids.next();
        jdbc.sql("""
                        insert into identity.users (id, display_name, email, locale, status)
                        values (:id, 'No Contact', :email, 'en-CA', 'active')
                        """)
                .param("id", unverified)
                .param("email", "nocontact-" + unverified.toLowerCase(Locale.ROOT) + "@example.ca")
                .update();
        var body = mvc.perform(post(PATH)
                        .with(TestJwt.customer(unverified))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"erasure\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codeSentTo").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(post(PATH + "/{id}/verification-code", JsonPath.<String>read(body, "$.id"))
                        .with(TestJwt.customer(unverified)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_mobile"));
    }

    @Test
    void thePreviewListsWhatDeletingWouldAffect() throws Exception {
        mvc.perform(get(PATH + "/erasure-preview").with(TestJwt.customer(person)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, ref, subtotal_cents, tax_cents)
                        values (:id, :u, 'food', 'placed', :ref, 2400, 120)
                        """)
                .param("id", Ids.next())
                .param("u", person)
                .param("ref", "FD-T" + person.substring(20))
                .update();
        mvc.perform(get(PATH + "/erasure-preview").with(TestJwt.customer(person)))
                .andExpect(jsonPath("$.items[0].code").value("open_orders"))
                .andExpect(jsonPath("$.items[0].count").value(1));
        mvc.perform(get(PATH + "/erasure-preview")).andExpect(status().isUnauthorized());
    }
}
