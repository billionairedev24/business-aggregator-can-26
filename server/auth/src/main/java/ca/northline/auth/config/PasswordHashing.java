package ca.northline.auth.config;

import ca.northline.auth.application.AccountProperties;
import ca.northline.auth.application.PasswordHasher;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * S-154: {@link PasswordHasher} = Argon2id (salt 16 bytes, hash 32 bytes, cost from {@code
 * northline.auth.accounts.password-hash}), hashes stored as {@code {argon2}$argon2id$v=19$m=…,t=…,p=…$salt$hash}.
 * Deliberately not a {@code PasswordEncoder} bean: the authorization server would pick it up for client secrets.
 */
@Slf4j
@Component
class PasswordHashing implements PasswordHasher {

    private final PasswordEncoder encoder;
    private final String decoy;

    PasswordHashing(AccountProperties props) {
        var cost = props.passwordHash();
        var argon2 = new Argon2PasswordEncoder(16, 32, cost.parallelism(), cost.memoryKib(), cost.iterations());
        this.encoder = new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
        var random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.decoy = encoder.encode(Base64.getEncoder().encodeToString(random));
        log.info("Passwords: Argon2id m={} KiB t={} p={}", cost.memoryKib(), cost.iterations(), cost.parallelism());
    }

    @Override
    public String hash(String password) {
        return java.util.Objects.requireNonNull(encoder.encode(password));
    }

    @Override
    public boolean matches(String password, String hash) {
        try {
            return encoder.matches(password, hash);
        } catch (IllegalArgumentException e) {
            return false; // a hash with an unknown {id}
        }
    }

    @Override
    public String decoy() {
        return decoy;
    }
}
