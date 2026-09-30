package ca.northline.worker.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** S-33 SSRF rules: what a webhook may never reach, and the local-development exception. */
class EgressPolicyTest {

    private final EgressPolicy strict = new EgressPolicy(false);
    private final EgressPolicy local = new EgressPolicy(true);

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, loopback",
        "127.8.9.10, loopback",
        "0.0.0.0, \"this network\"",
        "10.0.0.5, private network",
        "172.16.0.1, private network",
        "172.31.255.254, private network",
        "192.168.1.20, private network",
        "100.64.0.1, carrier-grade NAT",
        "100.100.100.200, carrier-grade NAT",
        "169.254.169.254, link-local / cloud metadata",
        "169.254.170.2, link-local / cloud metadata",
        "192.0.0.10, IETF / documentation range",
        "192.0.2.1, IETF / documentation range",
        "198.18.0.1, benchmarking range",
        "198.51.100.7, documentation range",
        "203.0.113.9, documentation range",
        "224.0.0.1, 'multicast, reserved or broadcast'",
        "255.255.255.255, 'multicast, reserved or broadcast'",
        "::1, loopback",
        "::, unspecified address",
        "fe80::1, link-local / site-local",
        "fc00::1, private network (unique local)",
        "fd00:ec2::254, private network (unique local)",
        "ff02::1, multicast",
        "2001:db8::1, documentation range",
        "::ffff:10.1.2.3, private network",
        "::ffff:169.254.169.254, link-local / cloud metadata",
        "64:ff9b::a9fe:a9fe, embeds link-local / cloud metadata",
        "2002:0a00:0001::1, embeds private network",
    })
    void refusesEveryNonPublicAddress(String address, String reason) throws Exception {
        assertThat(strict.refuse(InetAddress.getByName(address))).contains(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "142.250.72.14", "172.32.0.1", "100.128.0.1", "2607:f8b0:4004:800::200e"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(strict.refuse(InetAddress.getByName(address))).isEmpty();
    }

    @Test
    void oneRefusedAddressRefusesTheHost() throws Exception {
        var mixed = List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"));
        assertThat(strict.refuseAddresses("partner.example", mixed))
                .contains("partner.example resolves to 10.0.0.1 (private network)");
        assertThat(strict.refuseAddresses("partner.example", List.of())).contains("partner.example has no address");
    }

    @Test
    void onlyHttpsWithoutCredentials() {
        assertThat(strict.refuseUrl(URI.create("https://partner.example/hooks")))
                .isEmpty();
        assertThat(strict.refuseUrl(URI.create("http://partner.example/hooks")))
                .contains("only https:// URLs are allowed");
        assertThat(strict.refuseUrl(URI.create("http://localhost:8080/hooks")))
                .contains("only https:// URLs are allowed");
        assertThat(strict.refuseUrl(URI.create("ftp://partner.example/x"))).contains("only https:// URLs are allowed");
        assertThat(strict.refuseUrl(URI.create("https://user:pw@partner.example/")))
                .contains("credentials in the URL are not allowed");
        assertThat(strict.refuseUrl(URI.create("https:///nohost"))).contains("the URL has no host");
    }

    @Test
    void localDevelopmentAllowsHttpAndLoopbackOnly() throws Exception {
        assertThat(local.refuseUrl(URI.create("http://localhost:8080/hooks"))).isEmpty();
        assertThat(local.refuse(InetAddress.getByName("127.0.0.1"))).isEmpty();
        assertThat(local.refuse(InetAddress.getByName("::1"))).isEmpty();
        assertThat(local.refuse(InetAddress.getByName("10.0.0.1"))).contains("private network");
        assertThat(local.refuse(InetAddress.getByName("169.254.169.254"))).contains("link-local / cloud metadata");
    }
}
