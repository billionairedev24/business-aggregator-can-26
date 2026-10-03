package ca.northline.uat;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.uat.domain.FeedbackRules;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.domain.GoNoGo;
import ca.northline.uat.domain.GoNoGo.Coverage;
import ca.northline.uat.domain.GoNoGo.ReasonCode;
import ca.northline.uat.domain.Persona;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-121: what feedback keeps of a route and a text, the triage flow, and the go/no-go rule. */
class UatRulesTest {

    @Test
    void routesLoseHostQueryFragmentAndTokens() {
        assertThat(FeedbackRules.route("https://northline.example/checkout?session=abc#pay"))
                .isEqualTo("/checkout");
        assertThat(FeedbackRules.route("/b/01J9ZD3V00000000000000PWM1/orders?tab=open"))
                .isEqualTo("/b/01J9ZD3V00000000000000PWM1/orders");
        assertThat(FeedbackRules.route("/invite/short")).isEqualTo("/invite/:token");
        assertThat(FeedbackRules.route("/team-invitations/abc/accept")).isEqualTo("/team-invitations/:token/accept");
        assertThat(FeedbackRules.route("/x/aB3dE5gH7jK9mN1pQ3rS5tU7vW9"))
                .as("long mixed strings that aren't ULIDs")
                .isEqualTo("/x/:token");
        assertThat(FeedbackRules.route("/account/dana@example.ca")).doesNotContain("dana@example.ca");
        assertThat(FeedbackRules.route("?only=query")).isEqualTo("/");
        assertThat(FeedbackRules.route("/a".repeat(400))).hasSize(FeedbackRules.ROUTE_MAX);
    }

    @Test
    void textGoesThroughTheLogRedaction() {
        var text = FeedbackRules.text(
                "  Card 4242 4242 4242 4242, token Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc ");
        assertThat(text)
                .doesNotContain("4242 4242 4242 4242")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .startsWith("Card");
        assertThat(FeedbackRules.platform("Firefox 131\u0000 · macOS")).isEqualTo("Firefox 131 · macOS");
    }

    @Test
    void theTriageFlow() {
        assertThat(FeedbackState.NEW.next())
                .containsExactlyInAnyOrder(FeedbackState.TRIAGED, FeedbackState.WONT_FIX, FeedbackState.DUPLICATE);
        assertThat(FeedbackState.TRIAGED.canMoveTo(FeedbackState.ACCEPTED)).isTrue();
        assertThat(FeedbackState.ACCEPTED.canMoveTo(FeedbackState.FIXED)).isTrue();
        assertThat(FeedbackState.FIXED.canMoveTo(FeedbackState.VERIFIED)).isTrue();
        assertThat(FeedbackState.FIXED.canMoveTo(FeedbackState.ACCEPTED))
                .as("verification failed")
                .isTrue();
        assertThat(FeedbackState.VERIFIED.canMoveTo(FeedbackState.CLOSED)).isTrue();
        assertThat(FeedbackState.NEW.canMoveTo(FeedbackState.FIXED)).isFalse();
        for (var end : List.of(FeedbackState.CLOSED, FeedbackState.WONT_FIX, FeedbackState.DUPLICATE)) {
            assertThat(end.next()).as("reopen").containsExactly(FeedbackState.TRIAGED);
        }
    }

    @Test
    void goNeedsNoOpenBlockerAndEveryPersonaSignedOff() {
        var full = Arrays.stream(Persona.values())
                .map(p -> new Coverage(p, 2, 1, 1, 0))
                .toList();
        assertThat(GoNoGo.decide(0, 0, 0, full).go()).isTrue();

        var verdict = GoNoGo.decide(1, 2, 3, full);
        assertThat(verdict.go()).isFalse();
        assertThat(verdict.reasons())
                .extracting(GoNoGo.Reason::code)
                .containsExactly(
                        ReasonCode.BLOCKING_OPEN, ReasonCode.BLOCKING_UNVERIFIED, ReasonCode.BLOCKERS_UNTRIAGED);

        var gaps = new java.util.ArrayList<>(full);
        gaps.set(0, new Coverage(Persona.PROVIDER, 0, 0, 0, 0));
        gaps.set(1, new Coverage(Persona.SELLER, 3, 1, 0, 1));
        assertThat(GoNoGo.decide(0, 0, 0, gaps).reasons())
                .extracting(GoNoGo.Reason::code, GoNoGo.Reason::persona, GoNoGo.Reason::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ReasonCode.NO_PARTICIPANTS, Persona.PROVIDER, 0),
                        org.assertj.core.groups.Tuple.tuple(ReasonCode.SIGNOFFS_BLOCKED, Persona.SELLER, 1),
                        org.assertj.core.groups.Tuple.tuple(ReasonCode.SIGNOFFS_PENDING, Persona.SELLER, 1));
    }
}
