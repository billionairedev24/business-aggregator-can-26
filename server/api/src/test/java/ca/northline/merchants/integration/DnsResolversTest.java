package ca.northline.merchants.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.application.DnsResolver;
import ca.northline.merchants.application.DnsResolver.Answer;
import ca.northline.merchants.integration.DnsMessages.Question;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * S-31: the real DNS resolvers against stand-ins speaking the DNS wire format — DNS over HTTPS (RFC 8484, WireMock) and
 * the JDK's JNDI DNS client (a UDP server on localhost). Never run against a real resolver in CI.
 */
class DnsResolversTest {

    static final String TOKEN = "nl-abcdefghijklmnopqrstuvwxyz234567";

    /** The zone both stand-ins serve. */
    static List<DnsMessages.Answer> zone(Question q) {
        return switch (q.name() + " " + q.type()) {
            case "book.aspen.ca 5" -> List.of(new DnsMessages.Answer("book.aspen.ca", 5, "pages.northline.ca"));
            case "book.aspen.ca 1" ->
                List.of(
                        new DnsMessages.Answer("book.aspen.ca", 5, "pages.northline.ca"),
                        new DnsMessages.Answer("pages.northline.ca", 1, "192.0.2.10"));
            case "pages.northline.ca 1" -> List.of(new DnsMessages.Answer("pages.northline.ca", 1, "192.0.2.10"));
            case "pages.northline.ca 28" -> List.of(new DnsMessages.Answer("pages.northline.ca", 28, "2001:db8::10"));
            case "_northline-verify.book.aspen.ca 16" ->
                List.of(
                        new DnsMessages.Answer("_northline-verify.book.aspen.ca", 16, "google-site-verification=x"),
                        new DnsMessages.Answer("_northline-verify.book.aspen.ca", 16, TOKEN));
            case "long.aspen.ca 16" -> List.of(new DnsMessages.Answer("long.aspen.ca", 16, "x".repeat(300)));
            default -> List.of();
        };
    }

    static byte[] answer(byte[] query) {
        var q = DnsMessages.question(query);
        if (q.name().startsWith("servfail.")) {
            return DnsMessages.response(query, DnsWire.SERVFAIL, List.of());
        }
        var records = zone(q);
        var exists = !records.isEmpty()
                || (q.name().endsWith("aspen.ca") && !q.name().startsWith("gone."));
        return DnsMessages.response(query, exists ? DnsWire.NOERROR : DnsWire.NXDOMAIN, records);
    }

    /** What every resolver must say about the zone above. */
    static void behavesLikeAResolver(DnsResolver dns) {
        assertThat(dns.lookup("book.aspen.ca", DnsResolver.Type.CNAME))
                .isEqualTo(Answer.of(List.of("pages.northline.ca")));
        assertThat(dns.lookup("book.aspen.ca", DnsResolver.Type.A).values()).containsExactly("192.0.2.10");
        assertThat(dns.lookup("pages.northline.ca", DnsResolver.Type.AAAA).values())
                .hasSize(1);
        assertThat(dns.lookup("_northline-verify.book.aspen.ca", DnsResolver.Type.TXT)
                        .values())
                .contains(TOKEN, "google-site-verification=x");
        assertThat(dns.lookup("long.aspen.ca", DnsResolver.Type.TXT).values()).containsExactly("x".repeat(300));
        assertThat(dns.lookup("empty.aspen.ca", DnsResolver.Type.TXT)).isEqualTo(Answer.NONE);
        assertThat(dns.lookup("gone.example.ca", DnsResolver.Type.A))
                .as("NXDOMAIN")
                .isEqualTo(Answer.NONE);
        assertThat(dns.lookup("servfail.aspen.ca", DnsResolver.Type.A)).isEqualTo(Answer.ERROR);
    }

    @Nested
    class DnsOverHttps {

        static WireMockServer server;

        /** Answers each POSTed DNS message from the zone. */
        static final class Dns implements ResponseTransformerV2 {
            @Override
            public Response transform(Response response, ServeEvent event) {
                return Response.Builder.like(response)
                        .but()
                        .body(answer(event.getRequest().getBody()))
                        .build();
            }

            @Override
            public String getName() {
                return "dns";
            }

            @Override
            public boolean applyGlobally() {
                return false;
            }
        }

        @BeforeAll
        static void start() {
            server = new WireMockServer(wireMockConfig().dynamicPort().extensions(new Dns()));
            server.start();
            server.stubFor(post(urlPathEqualTo("/dns-query"))
                    .withHeader("Content-Type", equalTo("application/dns-message"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/dns-message")
                            .withTransformers("dns")));
            server.stubFor(
                    post(urlPathEqualTo("/broken")).willReturn(aResponse().withStatus(502)));
        }

        @AfterAll
        static void stop() {
            server.stop();
        }

        @Test
        void answersFromTheWireFormat() {
            behavesLikeAResolver(
                    new DohDnsResolver(URI.create(server.baseUrl() + "/dns-query"), Duration.ofSeconds(2)));
            server.verify(postRequestedFor(urlPathEqualTo("/dns-query"))
                    .withHeader("Accept", equalTo("application/dns-message")));
        }

        @Test
        void httpErrors_areResolverErrors() {
            var dns = new DohDnsResolver(URI.create(server.baseUrl() + "/broken"), Duration.ofSeconds(2));
            assertThat(dns.lookup("book.aspen.ca", DnsResolver.Type.A)).isEqualTo(Answer.ERROR);
            var nobody = new DohDnsResolver(URI.create("http://127.0.0.1:9/dns-query"), Duration.ofMillis(500));
            assertThat(nobody.lookup("book.aspen.ca", DnsResolver.Type.A)).isEqualTo(Answer.ERROR);
        }
    }

    @Nested
    class Jndi {

        @Test
        void answersOverUdp() throws Exception {
            try (var server = new UdpDns(DnsResolversTest::answer)) {
                behavesLikeAResolver(new JndiDnsResolver(List.of("127.0.0.1:" + server.port()), Duration.ofSeconds(1)));
            }
        }

        @Test
        void noServer_resolverError() throws Exception {
            int port;
            try (var socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            var dns = new JndiDnsResolver(List.of("127.0.0.1:" + port), Duration.ofMillis(200));
            assertThat(dns.lookup("book.aspen.ca", DnsResolver.Type.A)).isEqualTo(Answer.ERROR);
        }

        @Test
        void txtStringsAreJoined() {
            assertThat(JndiDnsResolver.text("\"nl-abc\" \"def\"")).isEqualTo("nl-abcdef");
            assertThat(JndiDnsResolver.text("nl-abc")).isEqualTo("nl-abc");
            assertThat(JndiDnsResolver.text("\"a \\\"quoted\\\" text\"")).isEqualTo("a \"quoted\" text");
        }
    }

    @Test
    void wireFormat_roundTrip_andCompressedNames() {
        var query = DnsWire.query(7, "Book.Aspen.CA", DnsResolver.Type.A);
        assertThat(DnsMessages.question(query)).isEqualTo(new Question(7, "book.aspen.ca", 1));
        var response = DnsWire.parse(DnsMessages.response(query, DnsWire.NOERROR, zone(DnsMessages.question(query))));
        assertThat(response.rcode()).isZero();
        assertThat(response.answers())
                .extracting(DnsWire.ResourceRecord::name, DnsWire.ResourceRecord::type, DnsWire.ResourceRecord::data)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("book.aspen.ca", 5, "pages.northline.ca"),
                        org.assertj.core.groups.Tuple.tuple("pages.northline.ca", 1, "192.0.2.10"));
    }

    /** A one-thread UDP DNS server on localhost answering with {@code handler}. */
    static final class UdpDns implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread thread;

        UdpDns(Function<byte[], byte[]> handler) throws SocketException {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> {
                var buffer = new byte[1500];
                while (!socket.isClosed()) {
                    try {
                        var packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        var query = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                        var reply = handler.apply(query);
                        socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                    } catch (IOException _) {
                        return;
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws InterruptedException {
            socket.close();
            thread.join(1000);
        }
    }
}
