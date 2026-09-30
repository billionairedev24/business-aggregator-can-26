package ca.northline.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * S-20: the auth session cookie ({@code server.servlet.session.cookie.*}, also used by Spring Session in the cloud) is
 * HttpOnly, Secure, SameSite=Lax and host-only everywhere, and {@code __Host-} prefixed in the cloud, so no sibling
 * subdomain can plant or overwrite it.
 */
class SessionCookieSettingsTest {

    private static Map<String, Object> cookie(String... profiles) throws Exception {
        var env = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (var profile : profiles) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml"))
                    .forEach(env.getPropertySources()::addLast);
        }
        loader.load("base", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        return Binder.get(env)
                .bindOrCreate("server.servlet.session.cookie", Bindable.mapOf(String.class, Object.class));
    }

    @Test
    void cloud_usesTheHostPrefix_secureAndHostOnly() throws Exception {
        var cookie = cookie("cloud");
        assertThat(cookie)
                .containsEntry("name", "__Host-NL_AUTH")
                .containsEntry("secure", true)
                .containsEntry("http-only", true)
                .containsEntry("same-site", "lax")
                .doesNotContainKey("domain");
    }

    @Test
    void local_keepsThePlainName_overHttp() throws Exception {
        var cookie = cookie("local");
        assertThat(cookie)
                .containsEntry("name", "NL_AUTH")
                .containsEntry("secure", false)
                .doesNotContainKey("domain");
    }
}
