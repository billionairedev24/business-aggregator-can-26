package ca.northline.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** Redesign phase 4: which hosts count as the developer's network, and that the switch defaults to off. */
class LocalNetworkDevicesTest {

    private static final RegisteredClient APP = RegisteredClient.withId("1")
            .clientId("mobile-consumer")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:3000/app/oauth2redirect")
            .redirectUri("ca.northline.app:/oauth2redirect")
            .build();

    @Test
    void privateNetworkHosts_areLocal_publicAndLoopbackAreNot() {
        for (var h :
                new String[] {"192.168.1.20", "10.0.2.2", "172.16.0.5", "172.31.255.1", "169.254.3.4", "macbook.local"
                }) {
            assertThat(LocalNetworkDevices.isLocalNetworkHost(h)).as(h).isTrue();
        }
        for (var h : new String[] {
            "8.8.8.8", "172.32.0.1", "192.169.0.1", "127.0.0.1", "localhost", "evil.example", "300.1.1.1", ".local", ""
        }) {
            assertThat(LocalNetworkDevices.isLocalNetworkHost(h)).as(h).isFalse();
        }
    }

    @Test
    void whenOn_aNetworkVariantOfALoopbackRedirectMatches_onlyThatOne() {
        var on = new LocalNetworkDevices(true);
        assertThat(on.registeredVariant(APP, "http://192.168.1.20:3000/app/oauth2redirect"))
                .isEqualTo("http://localhost:3000/app/oauth2redirect");
        assertThat(on.registeredVariant(APP, "http://192.168.1.20:3000/app/oauth2redirect#x"))
                .isNull();
        assertThat(on.registeredVariant(APP, "http://192.168.1.20:3000/app/oauth2redirect?next=/"))
                .isNull();
        assertThat(on.registeredVariant(APP, "http://u@192.168.1.20:3000/app/oauth2redirect"))
                .isNull();
        assertThat(on.registeredVariant(APP, "ca.northline.app:/elsewhere")).isNull();
    }

    @Test
    void offByDefault_nothingChanges() {
        var off = new LocalNetworkDevices(false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.registeredVariant(APP, "http://192.168.1.20:3000/app/oauth2redirect"))
                .isNull();
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServerName("192.168.1.20");
        assertThat(off.loginPage("http://localhost:3000/sign-in", request)).isEqualTo("http://localhost:3000/sign-in");
    }

    @Test
    void theSignInPage_followsANetworkHost_butNeverReplacesARealHost() {
        var on = new LocalNetworkDevices(true);
        var phone = new MockHttpServletRequest("GET", "/oauth2/authorize");
        phone.setServerName("10.0.2.2");
        assertThat(on.loginPage("http://localhost:3000/sign-in", phone)).isEqualTo("http://10.0.2.2:3000/sign-in");
        assertThat(on.loginPage("https://dev.northline.ca/sign-in", phone))
                .isEqualTo("https://dev.northline.ca/sign-in");
        var laptop = new MockHttpServletRequest("GET", "/oauth2/authorize");
        laptop.setServerName("localhost");
        assertThat(on.loginPage("http://localhost:3000/sign-in", laptop)).isEqualTo("http://localhost:3000/sign-in");
    }

    @Test
    void aBrowserOriginOnTheNetwork_passesWhenItsLoopbackTwinIsAllowed() {
        var on = new LocalNetworkDevices(true);
        var allowed = java.util.List.of("http://localhost:3000", "http://localhost:3100");
        assertThat(on.allowsOrigin("http://192.168.1.20:3000", allowed)).isTrue();
        assertThat(on.allowsOrigin("http://192.168.1.20:3001", allowed)).isFalse();
        assertThat(on.allowsOrigin("https://192.168.1.20:3000", allowed)).isFalse();
        assertThat(on.allowsOrigin("http://evil.example:3000", allowed)).isFalse();
        assertThat(on.allowsOrigin(null, allowed)).isFalse();
        assertThat(new LocalNetworkDevices(false).allowsOrigin("http://192.168.1.20:3000", allowed))
                .isFalse();
    }
}
