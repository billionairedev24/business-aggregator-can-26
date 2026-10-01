package ca.northline.merchants;

import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-76: the business's publishable key (Settings › API) and the public embed endpoint the website script calls. The
 * key is public; it only names the business, and can be limited to the business's own websites.
 */
class EmbedKeyApiTest extends IntegrationTest {

    static final String KEY = "/api/v1/merchants/{id}/settings/publishable-key";
    static final String EMBED = "/api/v1/public/embed";

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    OnboardingFlow flow;
    String owner;
    String merchantId;
    String slug;

    @BeforeEach
    void setUp() throws Exception {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc);
        owner = data.user("Ravi");
        merchantId = business(owner, "Aspen Wrench");
        slug = slugOf(merchantId, owner);
    }

    String business(String user, String name) throws Exception {
        var id = flow.start(user, "provider");
        flow.business(id, user, soleBusiness(name, "service.automotive.mobile-mechanic"))
                .andExpect(status().isOk());
        return id;
    }

    String slugOf(String id, String user) throws Exception {
        return JsonPath.read(
                mvc.perform(get("/api/v1/merchants/{id}/storefront", id).with(TestJwt.member(user)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.slug");
    }

    void publish(String id, String user) throws Exception {
        jdbc.sql("update merchants.merchants set status = 'active' where id = ?").param(id).update();
        mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", id).with(TestJwt.member(user)))
                .andExpect(status().isOk());
    }

    String issue(String id, String user) throws Exception {
        return JsonPath.read(
                mvc.perform(post(KEY, id).with(TestJwt.member(user)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.key");
    }

    ResultActions embed(String key, String store, String origin) throws Exception {
        var request = get(EMBED).param("key", key).param("store", store);
        return mvc.perform(origin == null ? request : request.header("Origin", origin));
    }

    ResultActions sites(String json) throws Exception {
        return mvc.perform(put(KEY + "/origins", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(owner)));
    }

    @Test
    void theOwnerIssuesAKey_andTheEmbedShowsThePublishedPage() throws Exception {
        mvc.perform(get(KEY, merchantId).with(TestJwt.member(owner))).andExpect(status().isNoContent());
        var key = issue(merchantId, owner);
        assertThat(key).matches("pk_live_[A-Za-z0-9_-]{32}");
        mvc.perform(get(KEY, merchantId).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.allowedOrigins.length()").value(0))
                .andExpect(jsonPath("$.scriptUrl").value("http://localhost:3000/embed.js"));

        embed(key, slug, "https://www.aspenwrench.example").andExpect(status().isNotFound()); // not published yet
        publish(merchantId, owner);
        embed(key, slug, "https://www.aspenwrench.example")
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.name").value("Aspen Wrench"))
                .andExpect(jsonPath("$.pageKind").value("business_page"))
                .andExpect(jsonPath("$.ctaLabel").value("book_visit"))
                .andExpect(jsonPath("$.brandColor").value("#2f5d3a"))
                .andExpect(jsonPath("$.path").value("/providers/" + slug));

        var actions = jdbc.sql("select action from developer.audit_log where merchant_id = ? and action like 'publishable_key.%'")
                .param(merchantId)
                .query(String.class)
                .list();
        assertThat(actions).containsExactly("publishable_key.issued");
    }

    @Test
    void rollingReplacesTheKey_theOldOneStopsAtOnce() throws Exception {
        publish(merchantId, owner);
        var first = issue(merchantId, owner);
        var second = issue(merchantId, owner);
        assertThat(second).isNotEqualTo(first);
        embed(first, slug, null).andExpect(status().isNotFound());
        embed(second, slug, null).andExpect(status().isOk());
        embed("pk_live_" + "x".repeat(32), slug, null).andExpect(status().isNotFound());
        embed("nl_live_secret", slug, null).andExpect(status().isNotFound());
    }

    @Test
    void aKeyShowsOnlyItsOwnBusinessesPage() throws Exception {
        publish(merchantId, owner);
        var otherOwner = data.user("Other");
        var other = business(otherOwner, "Birch Plumbing");
        publish(other, otherOwner);
        var otherKey = issue(other, otherOwner);
        embed(otherKey, slug, null).andExpect(status().isNotFound());
        embed(otherKey, slugOf(other, otherOwner), null).andExpect(status().isOk());
    }

    @Test
    void aKeyLimitedToSomeWebsitesAnswersOnlyThem() throws Exception {
        publish(merchantId, owner);
        var key = issue(merchantId, owner);
        sites("{\"allowedOrigins\":[\"www.aspenwrench.example\",\" https://Book.AspenWrench.example:443/ \",\"https://www.aspenwrench.example\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOrigins.length()").value(2))
                .andExpect(jsonPath("$.allowedOrigins[0]").value("https://www.aspenwrench.example"))
                .andExpect(jsonPath("$.allowedOrigins[1]").value("https://book.aspenwrench.example"));
        embed(key, slug, "https://www.aspenwrench.example")
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://www.aspenwrench.example"))
                .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Origin")));
        embed(key, slug, "https://copycat.example")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This embed key isn't set up for this website."));
        embed(key, slug, null).andExpect(status().isForbidden());
        // rolling keeps the websites
        var rolled = issue(merchantId, owner);
        embed(rolled, slug, "https://book.aspenwrench.example").andExpect(status().isOk());
        sites("{\"allowedOrigins\":[]}").andExpect(status().isOk());
        embed(rolled, slug, "https://copycat.example").andExpect(status().isOk());
    }

    @Test
    void websitesAreValidated() throws Exception {
        sites("{\"allowedOrigins\":[\"https://www.aspenwrench.example\"]}").andExpect(status().isNotFound()); // no key yet
        issue(merchantId, owner);
        for (var bad : new String[] {
            "http://www.aspenwrench.example", "https://www.aspenwrench.example/book", "https://user@host.example",
            "ftp://host.example", "https://"
        }) {
            sites("{\"allowedOrigins\":[\"%s\"]}".formatted(bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("allowedOrigins"))
                    .andExpect(jsonPath("$.errors[0].message").value("Enter a site address like https://www.example.com."));
        }
        var many = new StringBuilder("{\"allowedOrigins\":[");
        for (int i = 0; i < 11; i++) {
            many.append(i == 0 ? "" : ",").append("\"https://s").append(i).append(".example\"");
        }
        sites(many.append("]}").toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Up to 10 sites."));
        sites("{\"allowedOrigins\":[\"http://localhost:5173\"]}") // local development
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOrigins[0]").value("http://localhost:5173"));
    }

    @Test
    void membersSeeTheKey_onlyTheOwnerChangesIt() throws Exception {
        issue(merchantId, owner);
        var tech = data.user("Tech");
        data.member(merchantId, tech, MerchantRole.TECHNICIAN);
        mvc.perform(get(KEY, merchantId).with(TestJwt.member(tech))).andExpect(status().isOk());
        mvc.perform(post(KEY, merchantId).with(TestJwt.member(tech))).andExpect(status().isForbidden());
        mvc.perform(put(KEY + "/origins", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedOrigins\":[]}")
                        .with(TestJwt.member(tech)))
                .andExpect(status().isForbidden());
        mvc.perform(get(KEY, merchantId).with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(KEY, merchantId).with(TestJwt.memberWithoutMfa(owner))).andExpect(status().isForbidden());
    }
}
