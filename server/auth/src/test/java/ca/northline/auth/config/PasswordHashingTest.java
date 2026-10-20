package ca.northline.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.application.AccountProperties;
import ca.northline.auth.application.PasswordPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** S-154: Argon2id hashes in Spring Security's format; the dev seed's hash is the documented dev password's. */
class PasswordHashingTest {

    private final PasswordHashing hashing = new PasswordHashing(new AccountProperties(
            Duration.ofMinutes(15),
            Duration.ofSeconds(60),
            5,
            Duration.ofDays(7),
            new AccountProperties.PasswordHash(1024, 1, 1),
            false));

    @Test
    void hashesAreArgon2id_salted_andVerify() {
        var a = hashing.hash("correct horse battery staple");
        var b = hashing.hash("correct horse battery staple");
        assertThat(a).startsWith("{argon2}$argon2id$v=19$m=1024,t=1,p=1$").isNotEqualTo(b);
        assertThat(hashing.matches("correct horse battery staple", a)).isTrue();
        assertThat(hashing.matches("correct horse battery stapl", a)).isFalse();
        assertThat(hashing.matches("anything", "not a hash")).isFalse();
        assertThat(hashing.matches("anything", hashing.decoy())).isFalse();
    }

    @Test
    void normalisedInNfkc_soTheSamePasswordTypedTwoWaysMatches() {
        var composed = "café au lait chaud";
        var decomposed = "café au lait chaud";
        var hash = hashing.hash(PasswordPolicy.normalise(composed));
        assertThat(hashing.matches(PasswordPolicy.normalise(decomposed), hash)).isTrue();
    }

    @Test
    void theDevSeedHash_isNorthlineDevPassword() throws IOException {
        var sql = new String(
                Objects.requireNonNull(
                                getClass().getClassLoader().getResourceAsStream("db/seed-dev/V416__dev_passwords.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        var m = Pattern.compile("'(\\{argon2}[^']+)'").matcher(sql);
        assertThat(m.find()).isTrue();
        assertThat(hashing.matches("northline-dev-password", m.group(1))).isTrue();
    }
}
