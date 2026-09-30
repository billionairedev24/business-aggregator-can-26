package ca.northline.merchants.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.application.DnsResolver.Type;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DnsFindings;
import ca.northline.merchants.domain.DnsFindings.Pointing;
import ca.northline.merchants.domain.DnsFindings.Txt;
import ca.northline.merchants.domain.DomainPolicy;
import ca.northline.merchants.integration.FakeDnsResolver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S-31: what counts as "owned and pointing at us", against the in-memory DNS zone. */
class DnsInspectorTest {

    static final String TARGET = "pages.northline.ca";
    static final String TOKEN = "nl-abcdefghijklmnopqrstuvwxyz234567";

    FakeDnsResolver dns;
    DnsInspector inspector;

    @BeforeEach
    void setUp() {
        dns = new FakeDnsResolver(TARGET);
        inspector = inspector(List.of());
    }

    DnsInspector inspector(List<String> edgeAddresses) {
        return new DnsInspector(
                dns,
                new DomainSettings(
                        TARGET,
                        edgeAddresses,
                        List.of(),
                        DomainPolicy.DEFAULT,
                        20,
                        Duration.ofHours(1),
                        Duration.ZERO,
                        25));
    }

    DnsFindings inspect(String domain) {
        return inspector.inspect(new CustomDomain(domain), TOKEN);
    }

    @Test
    void cnameToPagesAndTheToken_ok() {
        dns.publish("book.aspen.ca", Type.CNAME, List.of("pages.northline.ca."));
        dns.publish("_northline-verify.book.aspen.ca", Type.TXT, List.of("some-other-site=1", TOKEN));
        assertThat(inspect("book.aspen.ca")).isEqualTo(new DnsFindings(Txt.FOUND, Pointing.CNAME));
    }

    @Test
    void cnameChainThroughTheMerchantsOwnName_ok() {
        dns.publish("www.aspen.ca", Type.CNAME, List.of("aspen-shop.hosting.example"));
        dns.publish("aspen-shop.hosting.example", Type.CNAME, List.of(TARGET));
        dns.publish("_northline-verify.www.aspen.ca", Type.TXT, List.of(TOKEN));
        assertThat(inspect("www.aspen.ca").pointing()).isEqualTo(Pointing.CNAME);
    }

    @Test
    void apexWithFlattenedAliasOrARecords_addressesMustAllBeOurs() {
        dns.publish("aspen.ca", Type.A, List.of(FakeDnsResolver.EDGE_ADDRESS));
        dns.publish("_northline-verify.aspen.ca", Type.TXT, List.of(TOKEN));
        assertThat(inspect("aspen.ca")).isEqualTo(new DnsFindings(Txt.FOUND, Pointing.ADDRESS));

        dns.publish("aspen.ca", Type.A, List.of(FakeDnsResolver.EDGE_ADDRESS, "203.0.113.99"));
        assertThat(inspect("aspen.ca").pointing())
                .as("half the traffic elsewhere")
                .isEqualTo(Pointing.ELSEWHERE);
    }

    @Test
    void configuredEdgeAddresses_anySpelling() {
        inspector = inspector(List.of("2001:db8::10"));
        dns.publish("aspen.ca", Type.AAAA, List.of("2001:db8:0:0:0:0:0:10"));
        dns.publish("_northline-verify.aspen.ca", Type.TXT, List.of(TOKEN));
        assertThat(inspect("aspen.ca").pointing()).isEqualTo(Pointing.ADDRESS);
    }

    @Test
    void pointsElsewhere_orNowhere() {
        dns.publish("book.aspen.ca", Type.CNAME, List.of("aspen.wixsite.example"));
        dns.publish("aspen.wixsite.example", Type.A, List.of("198.51.100.7"));
        assertThat(inspect("book.aspen.ca").pointing()).isEqualTo(Pointing.ELSEWHERE);
        assertThat(inspect("nothing.aspen.ca").pointing()).isEqualTo(Pointing.MISSING);
    }

    @Test
    void txt_missingOrSomeoneElses() {
        assertThat(inspect("book.aspen.ca").txt()).isEqualTo(Txt.MISSING);
        dns.publish("_northline-verify.book.aspen.ca", Type.TXT, List.of("nl-someoneelse"));
        assertThat(inspect("book.aspen.ca").txt()).isEqualTo(Txt.MISMATCH);
    }

    @Test
    void resolverDown_unknownNotMissing() {
        dns.fail("book.aspen.ca");
        dns.fail("_northline-verify.book.aspen.ca");
        var findings = inspect("book.aspen.ca");
        assertThat(findings).isEqualTo(new DnsFindings(Txt.UNKNOWN, Pointing.UNKNOWN));
        assertThat(findings.inconclusive()).isTrue();
    }

    @Test
    void ourOwnZonesAreBlocked() {
        var settings = new DomainSettings(
                "pages.staging.northline.ca",
                List.of(),
                List.of("northline-cdn.net"),
                DomainPolicy.DEFAULT,
                20,
                Duration.ofHours(1),
                Duration.ZERO,
                25);
        assertThat(settings.blocked(new CustomDomain("shop.northline-cdn.net"))).isTrue();
        assertThat(settings.blocked(new CustomDomain("northline-cdn.net"))).isTrue();
        assertThat(settings.blocked(new CustomDomain("mynorthline-cdn.net"))).isFalse();
        assertThat(settings.blockedSuffixes()).contains("staging.northline.ca");
    }
}
