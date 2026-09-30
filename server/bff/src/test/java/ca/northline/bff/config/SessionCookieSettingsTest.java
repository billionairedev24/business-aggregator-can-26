package ca.northline.bff.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
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

    private static StandardEnvironment environment(String... profiles) throws Exception {
        var env = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (var profile : profiles) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml"))
                    .forEach(env.getPropertySources()::addLast);
        }
        loader.load("base", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        return env;
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
    }
}
