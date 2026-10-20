package ca.northline.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.domain.AuthMessages;
import org.junit.jupiter.api.Test;

/** S-154, NIST 800-63B: length, the bundled list of common passwords, not the email; no composition rules. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    private String rule(String password) {
        var v = policy.check(password, "amara.osei@example.ca");
        return v == null ? "ok" : v.rule();
    }

    @Test
    void lengthInCodePoints_noCompositionRules() {
        assertThat(rule("elevenchars")).isEqualTo("length");
        assertThat(rule("all lower case words")).isEqualTo("ok");
        assertThat(rule("🍁🍁🍁🍁🍁🍁🍁🍁🍁🍁🍁x")).isEqualTo("ok"); // 12 code points, emoji welcome
        assertThat(rule("x".repeat(PasswordPolicy.MAX + 1))).isEqualTo("length");
        assertThat(policy.check("short", null))
                .extracting(InvalidInput.Violation::message)
                .isEqualTo(AuthMessages.PASSWORD_TOO_SHORT);
    }

    @Test
    void commonAndBreachedPasswords_areRefused_inAnyCase() {
        assertThat(rule("123456789012")).isEqualTo("common");
        assertThat(rule("PASSWORD1234")).isEqualTo("common");
        assertThat(rule("northline-dev-password")).isEqualTo("common");
        assertThat(rule("zzzzzzzzzzzzzz")).isEqualTo("common");
    }

    @Test
    void notTheEmail_norItsLocalPart() {
        assertThat(rule("Amara.Osei@example.ca")).isEqualTo("email");
        assertThat(policy.check("amara.osei.ok", "amara.osei.ok@example.ca")).isNotNull();
    }
}
