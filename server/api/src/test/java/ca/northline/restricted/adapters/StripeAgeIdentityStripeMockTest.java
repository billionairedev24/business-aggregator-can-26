package ca.northline.restricted.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.restricted.application.AgeIdentityProvider;
import ca.northline.shared.Conflict;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.support.StripeMock;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Customers' age checks on Stripe Identity against stripe-mock (Stripe's OpenAPI-validated mock; stateless fixtures):
 * the create request is valid for the pinned API version and keyed per customer and attempt, reading goes through the
 * {@code verified_outputs.dob} expand, and redaction is called. Never run against the real Stripe Identity (no
 * account); the age arithmetic is unit-tested here.
 */
class StripeAgeIdentityStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeAgeIdentity identity = new StripeAgeIdentity(StripeMock.client(recorder));

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        assertThat(recorder.sent())
                .allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(recorder.sent().stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:age-")));
    }

    @Test
    void startsADocumentSessionWithASelfie_keyedByCustomerAndAttempt() {
        // stripe-mock validates the request against Stripe's spec; its fixture has no hosted-flow `url`, which the
        // adapter refuses (a real new session always has one)
        var start = new AgeIdentityProvider.StartRequest(
                "01J9ZD3V0000000000000CUS01", 3, "http://localhost:3000/cart?age=done");
        assertThatThrownBy(() -> identity.start(start))
                .isInstanceOf(Conflict.class)
                .hasFieldOrPropertyWithValue("code", "age_check_unavailable");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions");
        assertThat(recorder.sent().getLast().idempotencyKeys())
                .containsExactly("nl1:age-session:01J9ZD3V0000000000000CUS01:3");
    }

    @Test
    void readsASession_withTheDateOfBirthExpanded() {
        var result = identity.read("vs_123", LocalDate.of(2026, 10, 4));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions/vs_123");
        // stripe-mock's fixture answers a session in some status; an age is only ever whole years (no date kept)
        assertThat(result.state()).isNotBlank();
        if (result.age() != null) {
            assertThat(result.state()).isEqualTo("verified");
            assertThat(result.age()).isNotNegative();
        }
    }

    @Test
    void redactsTheSessionOnceTheResultIsKept() {
        identity.redact("vs_123");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/identity/verification_sessions/vs_123/redact");
    }

    @Test
    void theAgeInWholeYears() {
        var today = LocalDate.of(2026, 10, 4);
        assertThat(StripeAgeIdentity.age(2007L, 10L, 4L, today)).isEqualTo(19);
        assertThat(StripeAgeIdentity.age(2007L, 10L, 5L, today)).isEqualTo(18);
        assertThat(StripeAgeIdentity.age(null, 10L, 5L, today)).isNull();
    }
}
