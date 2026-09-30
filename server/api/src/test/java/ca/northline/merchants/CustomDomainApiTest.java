package ca.northline.merchants;

import static ca.northline.merchants.OnboardingApiTest.err;
import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.CustomDomainChanged;
import ca.northline.merchants.application.DnsResolver.Type;
import ca.northline.merchants.application.StorefrontUseCases.CustomDomainJobs;
import ca.northline.merchants.integration.FakeDnsResolver;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-31 through the api: DNS instructions, verification against the in-memory DNS zone, the scheduler's jobs (checks,
 * edge reconcile with the local edge), grace period, claim conflicts, owners' notices and the public by-host lookup.
 */
@RecordApplicationEvents
class CustomDomainApiTest extends IntegrationTest {

    static final String TARGET = "pages.test.northline.ca"; // application-test.yml
    private static volatile boolean seeded;

    @Autowired
    ApplicationEvents events;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeDnsResolver dns;

    @Autowired
    CustomDomainJobs jobs;

    OnboardingFlow flow;

    /** One business with its page, its owner (with an email address) and a fresh domain name. */
    record Shop(String merchantId, String owner, String email, String domain) {}

    Shop shop;

    @BeforeEach
    void setUp() throws Exception {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc);
        shop = shop("Aspen Wrench");
    }

    Shop shop(String name) throws Exception {
        var owner = data.user(name + " Owner");
        var email = "owner-" + owner.toLowerCase(Locale.ROOT) + "@example.ca";
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params(email, owner)
                .update();
        var merchantId = flow.start(owner, "provider");
        flow.business(merchantId, owner, soleBusiness(name, "service.automotive.mobile-mechanic"))
                .andExpect(status().isOk());
        return new Shop(merchantId, owner, email, "book-" + merchantId.toLowerCase(Locale.ROOT) + ".example.ca");
    }

    ResultActions connect(Shop s, String domain) throws Exception {
        return mvc.perform(patch("/api/v1/merchants/{id}/storefront", s.merchantId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customDomain\":\"%s\"}".formatted(domain))
                .with(TestJwt.member(s.owner())));
    }

    ResultActions checkNow(Shop s) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", s.merchantId())
                .with(TestJwt.member(s.owner())));
    }

    String token(Shop s) {
        return jdbc.sql("select custom_domain_token from merchants.storefronts where merchant_id = ?")
                .param(s.merchantId())
                .query(String.class)
                .single();
    }

    String state(Shop s) {
        return jdbc.sql(
                        "select coalesce(custom_domain_status, 'none') from merchants.storefronts where merchant_id = ?")
                .param(s.merchantId())
                .query(String.class)
                .single();
    }

    /** What the merchant adds at their DNS host. */
    void addRecords(Shop s, String domain) {
        dns.publish(domain, Type.CNAME, List.of(TARGET));
        dns.publish("_northline-verify." + domain, Type.TXT, List.of(token(s)));
    }

    void approveAndPublish(Shop s) throws Exception {
        jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                .param(s.merchantId())
                .update();
        mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", s.merchantId())
                        .with(TestJwt.member(s.owner())))
                .andExpect(status().isOk());
    }

    /** The jobs work through every due domain of the shared database; run them until ours moved. */
    void checkUntil(Shop s, String expected) {
        for (int i = 0; i < 40 && !state(s).equals(expected); i++) {
            jobs.checkDue();
        }
        assertThat(state(s)).isEqualTo(expected);
    }

    Shop live() throws Exception {
        connect(shop, shop.domain()).andExpect(status().isOk());
        addRecords(shop, shop.domain());
        checkNow(shop).andExpect(jsonPath("$.customDomainStatus").value("verified"));
        approveAndPublish(shop);
        jobs.reconcileEdge();
        assertThat(state(shop)).isEqualTo("live");
        return shop;
    }

    List<CustomDomainChanged> changes(Shop s) {
        return events.stream(CustomDomainChanged.class)
                .filter(e -> e.merchantId().equals(s.merchantId()))
                .toList();
    }

    @Nested
    class Instructions {

        @Test
        void subdomain_cnameToPagesAndTheOwnershipTxt() throws Exception {
            connect(shop, "https://" + shop.domain().toUpperCase(Locale.ROOT) + "/")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customDomain").value(shop.domain()))
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"))
                    .andExpect(jsonPath("$.customDomainTarget").value(TARGET))
                    .andExpect(jsonPath("$.customDomainSetup.target").value(TARGET))
                    .andExpect(jsonPath("$.customDomainSetup.apex").value(false))
                    .andExpect(jsonPath("$.customDomainSetup.records[*].type", contains("CNAME", "TXT")))
                    .andExpect(jsonPath("$.customDomainSetup.records[0].name").value(shop.domain()))
                    .andExpect(jsonPath("$.customDomainSetup.records[0].value").value(TARGET))
                    .andExpect(
                            jsonPath("$.customDomainSetup.records[1].name").value("_northline-verify." + shop.domain()))
                    .andExpect(jsonPath("$.customDomainSetup.records[1].value").value(startsWith("nl-")));
            assertThat(token(shop)).matches("nl-[a-z2-7]{32}");
            assertThat(changes(shop)).singleElement().satisfies(e -> {
                assertThat(e.status()).isEqualTo("pending");
                assertThat(e.previousStatus()).isNull();
                assertThat(e.domain()).isEqualTo(shop.domain());
            });
        }

        @Test
        void apex_aliasGuidance() throws Exception {
            var apex = "aspen-" + shop.merchantId().toLowerCase(Locale.ROOT) + ".ca";
            connect(shop, apex)
                    .andExpect(jsonPath("$.customDomainSetup.apex").value(true))
                    .andExpect(jsonPath("$.customDomainSetup.records[*].type", contains("ALIAS", "TXT")))
                    .andExpect(jsonPath("$.customDomainSetup.records[0].value").value(TARGET));

            // flattened at the DNS host: A records with the edge's address
            dns.publish(apex, Type.A, List.of(FakeDnsResolver.EDGE_ADDRESS));
            dns.publish("_northline-verify." + apex, Type.TXT, List.of(token(shop)));
            checkNow(shop).andExpect(jsonPath("$.customDomainStatus").value("verified"));
        }

        @Test
        void aNewDomainGetsANewToken_theSameOneChangesNothing() throws Exception {
            connect(shop, shop.domain());
            var first = token(shop);
            connect(shop, shop.domain()).andExpect(status().isOk());
            assertThat(token(shop)).isEqualTo(first);
            connect(shop, "www." + shop.domain());
            assertThat(token(shop)).isNotEqualTo(first);
            connect(shop, "")
                    .andExpect(jsonPath("$.customDomain").doesNotExist())
                    .andExpect(jsonPath("$.customDomainSetup").doesNotExist());
            assertThat(changes(shop)).last().satisfies(e -> {
                assertThat(e.status()).isNull();
                assertThat(e.previousStatus()).isEqualTo("pending");
            });
        }

        @Test
        void ourOwnZonesAreRefused() throws Exception {
            connect(shop, "shop.northline-cdn.net")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("customDomain", "That domain can't be connected to a Northline page."));
            connect(shop, "shop.pages.test.northline.ca")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("customDomain", "Enter a domain like book.yourbusiness.ca."));
        }
    }

    @Nested
    class Verification {

        @Test
        void checkNow_saysWhatIsMissing_thenVerifies() throws Exception {
            connect(shop, shop.domain());
            checkNow(shop)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"))
                    .andExpect(jsonPath("$.customDomainSetup.problem").value("txt_missing"))
                    .andExpect(jsonPath("$.customDomainSetup.checkedAt").exists())
                    .andExpect(jsonPath("$.customDomainSetup.nextCheckAt").exists());

            dns.publish("_northline-verify." + shop.domain(), Type.TXT, List.of(token(shop)));
            checkNow(shop).andExpect(jsonPath("$.customDomainSetup.problem").value("no_record"));

            dns.publish(shop.domain(), Type.CNAME, List.of("somewhere-else.example.net"));
            checkNow(shop).andExpect(jsonPath("$.customDomainSetup.problem").value("not_pointing"));

            dns.publish(shop.domain(), Type.CNAME, List.of(TARGET));
            checkNow(shop)
                    .andExpect(jsonPath("$.customDomainStatus").value("verified"))
                    .andExpect(jsonPath("$.customDomainSetup.problem").doesNotExist())
                    .andExpect(jsonPath("$.customDomainSetup.verifiedAt").exists());
        }

        @Test
        void theSchedulerChecksPendingDomains() throws Exception {
            connect(shop, shop.domain());
            addRecords(shop, shop.domain());
            checkUntil(shop, "verified");
        }

        @Test
        void verified_publishedPageOfAnActiveBusiness_goesLive_ownersTold_servedByHost() throws Exception {
            connect(shop, shop.domain());
            addRecords(shop, shop.domain());
            checkNow(shop);
            jobs.reconcileEdge();
            assertThat(state(shop)).as("not published yet: stays off the edge").isEqualTo("verified");
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", shop.domain()))
                    .andExpect(status().isNotFound());

            approveAndPublish(shop);
            jobs.reconcileEdge();
            assertThat(state(shop)).isEqualTo("live");

            mvc.perform(get("/api/v1/public/storefronts/by-host")
                            .param("host", shop.domain().toUpperCase(Locale.ROOT) + ":443"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=60, public"))
                    .andExpect(jsonPath("$.customDomain").value(shop.domain()))
                    .andExpect(jsonPath("$.business.displayName").value("Aspen Wrench"));
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", "unknown.example.ca"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", "not a host"))
                    .andExpect(status().isNotFound());

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !emails.to(shop.email()).isEmpty());
            assertThat(emails.to(shop.email())).singleElement().satisfies(mail -> {
                assertThat(mail.subject()).isEqualTo(shop.domain() + " now shows the page of Aspen Wrench");
                assertThat(mail.text()).contains("https://" + shop.domain());
            });
        }

        @Test
        void pausedBusiness_offTheEdge() throws Exception {
            live();
            jdbc.sql("update merchants.merchants set status = 'suspended' where id = ?")
                    .param(shop.merchantId())
                    .update();
            jobs.reconcileEdge();
            assertThat(state(shop)).isEqualTo("verified");
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", shop.domain()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class GracePeriod {

        @Test
        void recordsGone_keepsServing_thenUnverified_ownersToldTwice() throws Exception {
            live();
            dns.remove(shop.domain());
            jdbc.sql("update merchants.storefronts set custom_domain_next_check_at = now() - interval '1 second'"
                            + " where merchant_id = ?")
                    .param(shop.merchantId())
                    .update();
            for (int i = 0; i < 40 && changes(shop).stream().noneMatch(e -> "dns_lost".equals(e.notice())); i++) {
                jobs.checkDue();
            }
            assertThat(state(shop)).isEqualTo("live");
            mvc.perform(get("/api/v1/merchants/{id}/storefront", shop.merchantId())
                            .with(TestJwt.member(shop.owner())))
                    .andExpect(jsonPath("$.customDomainSetup.graceEndsAt").exists())
                    .andExpect(jsonPath("$.customDomainSetup.problem").value("no_record"));
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", shop.domain()))
                    .andExpect(status().isOk());

            // three days later
            jdbc.sql("""
                            update merchants.storefronts
                               set custom_domain_dns_lost_at = now() - interval '73 hours',
                                   custom_domain_next_check_at = now() - interval '1 second'
                             where merchant_id = ?""").param(shop.merchantId()).update();
            checkUntil(shop, "pending");
            jobs.reconcileEdge();
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", shop.domain()))
                    .andExpect(status().isNotFound());

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> emails.to(shop.email()).size() == 3);
            assertThat(emails.to(shop.email()))
                    .extracting(m -> m.subject())
                    .containsExactlyInAnyOrder(
                            shop.domain() + " now shows the page of Aspen Wrench",
                            "Action needed: " + shop.domain() + " no longer points at Northline (Aspen Wrench)",
                            shop.domain() + " was disconnected from the page of Aspen Wrench");
        }
    }

    @Nested
    class ClaimConflicts {

        @Test
        void unprovenHolder_withoutItsTxt_released_ownersTold() throws Exception {
            var squatter = shop("Squatter Co");
            connect(squatter, shop.domain()).andExpect(status().isOk());

            connect(shop, shop.domain())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customDomainStatus").value("pending"));
            assertThat(state(squatter)).isEqualTo("none");
            assertThat(changes(squatter))
                    .last()
                    .satisfies(e -> assertThat(e.notice()).isEqualTo("released"));
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !emails.to(squatter.email()).isEmpty());
            assertThat(emails.to(squatter.email()).getFirst().subject())
                    .isEqualTo(shop.domain() + " was disconnected from the page of Squatter Co");
        }

        @Test
        void unprovenHolder_whoseTxtIsThere_keepsIt() throws Exception {
            var first = shop("First Co");
            connect(first, shop.domain());
            dns.publish("_northline-verify." + shop.domain(), Type.TXT, List.of(token(first)));

            connect(shop, shop.domain())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("customDomain", "That domain is already connected to another page."));
            assertThat(state(first)).isEqualTo("pending");
        }

        @Test
        void servingHolder_keepsIt_butIsReverified() throws Exception {
            live();
            var claimant = shop("Claimant Co");
            dns.remove("_northline-verify." + shop.domain()); // the DNS owner removed the holder's proof

            connect(claimant, shop.domain())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err("customDomain", "That domain is already connected to another page."));
            assertThat(state(shop)).isEqualTo("live");
            assertThat(changes(shop)).last().satisfies(e -> {
                assertThat(e.notice()).isEqualTo("dns_lost");
                assertThat(e.graceEndsAt()).isNotNull();
            });
        }
    }

    @Nested
    class Access {

        @Test
        void checkNow_ownerOnly_withMfa() throws Exception {
            connect(shop, shop.domain());
            var stranger = data.user("Stranger");
            mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", shop.merchantId())
                            .with(TestJwt.member(stranger)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            var tech = data.user("Tech");
            data.member(shop.merchantId(), tech, MerchantRole.TECHNICIAN);
            mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", shop.merchantId())
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post("/api/v1/merchants/{id}/storefront/domain/verify", shop.merchantId())
                            .with(TestJwt.memberWithoutMfa(shop.owner())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void checkNow_withoutADomain_conflict() throws Exception {
            checkNow(shop)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("no_custom_domain"));
        }

        @Test
        void publishingNeedsAProvenDomain() throws Exception {
            jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                    .param(shop.merchantId())
                    .update();
            connect(shop, shop.domain());
            mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", shop.merchantId())
                            .with(TestJwt.member(shop.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(err(
                            "customDomain", "Point the CNAME at pages.northline.ca and verify it before publishing."));
            String body = mvc.perform(get("/api/v1/merchants/{id}/storefront", shop.merchantId())
                            .with(TestJwt.member(shop.owner())))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat((String) JsonPath.read(body, "$.customDomainStatus")).isEqualTo("pending");
        }
    }
}
