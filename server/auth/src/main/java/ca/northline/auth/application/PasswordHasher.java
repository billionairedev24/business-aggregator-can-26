package ca.northline.auth.application;

/**
 * Outbound port (S-154): one-way password hashes. The adapter is Argon2id (Spring Security's {@code
 * Argon2PasswordEncoder} on BouncyCastle), stored with an {@code {argon2}} prefix so a later algorithm can be added
 * beside it. Callers pass the password already {@link PasswordPolicy#normalise normalised}.
 */
public interface PasswordHasher {

    String hash(String password);

    /** Constant work for a given hash's parameters; false for a malformed hash. */
    boolean matches(String password, String hash);

    /** A hash of a random secret: checked when an account has no password, so the answer takes the same time. */
    String decoy();
}
