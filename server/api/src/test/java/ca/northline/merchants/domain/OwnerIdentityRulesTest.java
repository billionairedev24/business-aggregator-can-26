package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.merchants.domain.OwnerIdentityCheck.SessionUpdate;
import ca.northline.shared.Conflict;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** S-22 rules: name matching, applying Stripe's updates in order, and the kyc row's roll-up. */
class OwnerIdentityRulesTest {

    static final Instant T = Instant.parse("2026-09-30T16:00:00Z");

    @ParameterizedTest(name = "{0} vs {1} {2} → {3}")
    @CsvSource(
            quoteCharacter = '"',
            value = {
                "Ravi Sandhu,        RAVI,          SANDHU,        MATCH",
                "Ravi Singh Sandhu,  Ravi,          Sandhu,        MATCH",
                "Ravi Sandhu,        Ravi Singh,    Sandhu,        MATCH",
                "Hélène Côté-Roy,    HELENE,        COTE ROY,      MATCH",
                "Amara O'Brien,      Amara,         OBRIEN,        MATCH",
                "Amara Okafor,       Amara,         Okafo,         MISMATCH",
                "Priya Sandhu,       Ravi,          Sandhu,        MISMATCH",
            })
    void names(String legal, String first, String last, IdentityMatch expected) {
        assertThat(PersonNames.compare(legal, first, last)).isEqualTo(expected);
    }

    @Test
    void noNameRead_isUnavailable() {
        assertThat(PersonNames.compare("Ravi Sandhu", null, " ")).isEqualTo(IdentityMatch.UNAVAILABLE);
    }

    OwnerIdentityCheck check() {
        return OwnerIdentityCheck.start("c1", "m1", "p1", "vs_1", OwnerIdentityCheck.Delivery.SELF, null, "u1", T);
    }

    static SessionUpdate update(String session, IdentitySessionState state, Instant at) {
        return new SessionUpdate(session, state, null, IdentityMatch.MATCH, IdentityMatch.UNAVAILABLE, at);
    }

    @Test
    void verified_isFinal_andOlderOrForeignUpdatesAreIgnored() {
        var c = check();
        assertThat(c.apply(update("vs_1", IdentitySessionState.PROCESSING, T.plusSeconds(1)), T))
                .isTrue();
        assertThat(c.apply(update("vs_1", IdentitySessionState.VERIFIED, T.plusSeconds(5)), T))
                .isTrue();
        assertThat(c.getStatus()).isEqualTo(IdentityCheckStatus.VERIFIED);
        assertThat(c.getVerifiedAt()).isEqualTo(T);
        assertThat(c.apply(update("vs_1", IdentitySessionState.CANCELED, T.plusSeconds(9)), T))
                .isFalse();

        var other = check();
        assertThat(other.apply(update("vs_2", IdentitySessionState.VERIFIED, T), T))
                .isFalse();
        other.apply(update("vs_1", IdentitySessionState.PROCESSING, T.plusSeconds(10)), T);
        assertThat(other.apply(update("vs_1", IdentitySessionState.REQUIRES_INPUT, T), T))
                .isFalse();
        assertThat(other.getStatus()).isEqualTo(IdentityCheckStatus.PROCESSING);
    }

    @Test
    void mismatchGoesToReview_errorsToRetry_andRestartRules() {
        var c = check();
        c.apply(
                new SessionUpdate(
                        "vs_1", IdentitySessionState.VERIFIED, null, IdentityMatch.MATCH, IdentityMatch.MISMATCH, T),
                T);
        assertThat(c.getStatus()).isEqualTo(IdentityCheckStatus.REVIEW);
        assertThat(c.getVerifiedAt()).isNull();
        assertThatThrownBy(c::requireRestartable).isInstanceOf(Conflict.class);

        var r = check();
        r.apply(
                new SessionUpdate(
                        "vs_1",
                        IdentitySessionState.REQUIRES_INPUT,
                        "document_expired",
                        IdentityMatch.UNAVAILABLE,
                        IdentityMatch.UNAVAILABLE,
                        T),
                T);
        assertThat(r.getStatus()).isEqualTo(IdentityCheckStatus.RETRY);
        r.restart("vs_2", OwnerIdentityCheck.Delivery.EMAIL, "a@b.ca", "u1", T);
        assertThat(r.getAttempts()).isEqualTo(2);
        assertThat(r.getLastError()).isNull();
        assertThat(r.getStatus()).isEqualTo(IdentityCheckStatus.PENDING);
    }

    @Test
    void kycRowRollup() {
        var v = IdentityCheckStatus.VERIFIED;
        assertThat(OwnerIdentityCheck.rollup(0, List.of())).isEqualTo(VerificationStatus.TODO);
        assertThat(OwnerIdentityCheck.rollup(2, List.of(v))).isEqualTo(VerificationStatus.TODO);
        assertThat(OwnerIdentityCheck.rollup(2, List.of(v, v))).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(OwnerIdentityCheck.rollup(2, List.of(v, IdentityCheckStatus.REVIEW)))
                .isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(OwnerIdentityCheck.rollup(2, List.of(v, IdentityCheckStatus.PROCESSING)))
                .isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(OwnerIdentityCheck.rollup(2, List.of(v, IdentityCheckStatus.RETRY)))
                .isEqualTo(VerificationStatus.TODO);
    }
}
