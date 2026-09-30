package ca.northline.merchants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.merchants.application.IdentityVerification;
import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.shared.Conflict;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.support.StripeMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Stripe Identity against stripe-mock (Stripe's OpenAPI-validated mock; stateless fixtures): the create request is
 * valid for the pinned API version, keyed per check and attempt, and reading a session goes through the expand path.
 * Never run against the real Stripe Identity (no account); the name/date-of-birth comparison is unit-tested.
 */
class StripeIdentityVerificationStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeIdentityVerification identity = new StripeIdentityVerification(StripeMock.client(recorder));

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        assertThat(recorder.sent())
                .allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(recorder.sent().stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:identity-")));
    }

    @Test
    void startsADocumentSessionWithASelfie_keyedByCheckAndAttempt() {
        // stripe-mock validates the request against Stripe's spec; its fixture has no hosted-flow `url`, which the
        // adapter refuses (a real new session always has one)
        var start = new IdentityVerification.StartRequest(
                "01J9ZD3V00000000000000PWM1",
                "01J9ZD3V0000000000000CHK01",
                "01J9ZD3V0000000000000PRN01",
                2,
                "http://localhost:3100/identity/done",
                "priya@example.test");
        assertThatThrownBy(() -> identity.start(start))
                .isInstanceOf(Conflict.class)
                .hasFieldOrPropertyWithValue("code", "identity_unavailable");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions");
        assertThat(recorder.sent().getLast().idempotencyKeys())
                .containsExactly("nl1:identity-session:01J9ZD3V0000000000000CHK01:2");
    }

    @Test
    void readsASession_withVerifiedOutputsExpanded() {
        var result = identity.read("vs_123", new IdentityVerification.Expected("Jenny Rosen", null));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions/vs_123");
        // stripe-mock's fixture is `requires_input` without outputs: nothing to compare
        assertThat(result.nameMatch()).isEqualTo(IdentityMatch.UNAVAILABLE);
        assertThat(result.dobMatch()).isEqualTo(IdentityMatch.UNAVAILABLE);
    }

    @Test
    void cancelsAReplacedSession() {
        identity.cancel("vs_123");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions/vs_123/cancel");
    }
}
