package ca.northline.bff.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * S-20: the BFF session cookie ({@code server.servlet.session.cookie.*}, also used by Spring Session in the cloud) is
 * HttpOnly, Secure, SameSite=Lax and host-only everywhere, and {@code __Host-} prefixed in the cloud together with the
 * CSRF cookie, so no sibling subdomain can plant or overwrite them.
 */
class SessionCookieSettingsTest {

    private static Map<String, Object> cookie(String... profiles) throws Exception {
        return Binder.get(environment(profiles))
                .bindOrCreate("server.servlet.session.cookie", Bindable.mapOf(String.class, Object.class));
    }

    /** The files of {@code profiles} (last wins, as Boot orders them) over application.yml. */
    private static StandardEnvironment environment(String... profiles) throws Exception {
        var env = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        var active = List.of(profiles);
        for (var profile : active.reversed()) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml")).stream()
                    .filter(doc -> applies(doc, active))
                    .forEach(env.getPropertySources()::addLast);
        }
        loader.load("base", new ClassPathResource("application.yml")).stream()
                .filter(doc -> applies(doc, active))
                .forEach(env.getPropertySources()::addLast);
        return env;
    }

    /**
     * {@code spring.config.activate.on-profile} of one YAML document: absent, {@code p}, {@code !p}, or several of those
     * joined by {@code &} (S-90: {@code "!consumer & !console"}).
     */
    private static boolean applies(PropertySource<?> doc, List<String> active) {
        var value = doc.getProperty("spring.config.activate.on-profile");
        if (value == null) {
            return true;
        }
        for (var term : value.toString().split("&")) {
            var expr = term.strip();
            var ok = expr.startsWith("!") ? !active.contains(expr.substring(1)) : active.contains(expr);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String csrfCookie(String... profiles) throws Exception {
        return Binder.get(environment(profiles))
                .bind("northline.bff.csrf-cookie-name", String.class)
                .orElse("XSRF-TOKEN");
    }

    @Test
    void cloud_usesTheHostPrefix_secureAndHostOnly() throws Exception {
        var cookie = cookie("cloud");
        assertThat(cookie)
                .containsEntry("name", "__Host-NL_STUDIO")
                .containsEntry("secure", true)
                .containsEntry("http-only", true)
                .containsEntry("same-site", "lax")
                .doesNotContainKey("domain");
    }

    @Test
    void local_keepsThePlainName_overHttp() throws Exception {
        var cookie = cookie("local");
        assertThat(cookie)
                .containsEntry("name", "NL_STUDIO")
                .containsEntry("secure", false)
                .doesNotContainKey("domain");
    }

    @Test
    void csrfCookie_isHostPrefixedInTheCloudOnly() throws Exception {
        assertThat(csrfCookie("cloud")).isEqualTo("__Host-XSRF-TOKEN");
        assertThat(csrfCookie("local")).isEqualTo("XSRF-TOKEN");
        assertThat(csrfCookie("cloud", "consumer")).isEqualTo("__Host-XSRF-TOKEN");
    }

    @Test
    void theConsumerBff_hasItsOwnCookie_hostPrefixedInTheCloud() throws Exception {
        assertThat(cookie("cloud", "consumer"))
                .containsEntry("name", "__Host-NL_CONSUMER")
                .containsEntry("secure", true)
                .containsEntry("http-only", true)
                .containsEntry("same-site", "lax")
                .doesNotContainKey("domain");
        assertThat(cookie("local", "consumer"))
                .containsEntry("name", "NL_CONSUMER")
                .containsEntry("secure", false);
    }

    @Test
    void theConsoleBff_hasItsOwnCookie_hostPrefixedInTheCloud() throws Exception {
        assertThat(cookie("cloud", "console"))
                .containsEntry("name", "__Host-NL_CONSOLE")
                .containsEntry("secure", true)
                .containsEntry("http-only", true)
                .containsEntry("same-site", "lax")
                .doesNotContainKey("domain");
        assertThat(cookie("local", "console"))
                .containsEntry("name", "NL_CONSOLE")
                .containsEntry("secure", false);
        assertThat(csrfCookie("cloud", "console")).isEqualTo("__Host-XSRF-TOKEN");
    }
}
