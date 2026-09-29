package ca.northline.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code GET /api/v1/me} and the V020 identity constraints. */
class MeApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String user(String first, String last, String email, String phone) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, phone, locale,
                               mfa_primary, status, created_at)
                        values (:id, :f, :l, :f || ' ' || :l, :e, :p, 'fr-CA', 'passkey', 'active',
                                '2026-05-02T05:00:00Z')""")
                .param("id", id)
                .param("f", first)
                .param("l", last)
                .param("e", email)
                .param("p", phone)
                .update();
        return id;
    }

    private static String unique() {
        return Long.toString(System.nanoTime() % 10_000_000L);
    }

    @Test
    void returnsTheSignedInProfile() throws Exception {
        var n = unique();
        var id = user("Amara", "Osei", "amara" + n + "@example.ca", "+1587" + String.format("%07d", Long.parseLong(n)));
        mvc.perform(get("/api/v1/me").with(TestJwt.member(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.firstName").value("Amara"))
                .andExpect(jsonPath("$.lastName").value("Osei"))
                .andExpect(jsonPath("$.initials").value("AO"))
                .andExpect(jsonPath("$.locale").value("fr-CA"))
                .andExpect(jsonPath("$.memberSince").value("2026-05-01")) // 05:00 UTC is still 1 May in Edmonton
                .andExpect(jsonPath("$.mfaPrimary").value("passkey"))
                .andExpect(jsonPath("$.mfa").value(true));
    }

    @Test
    void withoutSecondFactor_mfaIsFalse() throws Exception {
        var n = unique();
        var id = user("Jo", "Gill", "jo" + n + "@example.ca", "+1825" + String.format("%07d", Long.parseLong(n)));
        mvc.perform(get("/api/v1/me").with(TestJwt.customer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa").value(false));
    }

    @Test
    void rowsWithOnlyADisplayName_splitIt() throws Exception {
        var id = data.user("Priya Sandhu");
        mvc.perform(get("/api/v1/me").with(TestJwt.customer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Priya"))
                .andExpect(jsonPath("$.lastName").value("Sandhu"))
                .andExpect(jsonPath("$.initials").value("PS"));
    }

    @Test
    void unknownUser_is404() throws Exception {
        mvc.perform(get("/api/v1/me").with(TestJwt.customer(Ids.next()))).andExpect(status().isNotFound());
    }

    @Test
    void signedOut_is401() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        var n = unique();
        user("A", "B", "dup" + n + "@example.ca", null);
        assertThatThrownBy(() -> user("C", "D", "DUP" + n + "@Example.ca", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void phoneIsUnique() {
        var phone = "+1403" + String.format("%07d", Long.parseLong(unique()));
        user("A", "B", null, phone);
        assertThatThrownBy(() -> user("C", "D", null, phone)).isInstanceOf(DuplicateKeyException.class);
    }
}
