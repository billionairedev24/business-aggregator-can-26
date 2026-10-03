package ca.northline.golive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.golive.domain.Coverage;
import ca.northline.golive.domain.Coverage.Span;
import ca.northline.golive.domain.HypercareRotation;
import ca.northline.golive.domain.LaunchRequest;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-118's pure rules: on-call coverage, the hypercare rotation, the two-person launch request. */
class GoLiveRulesTest {

    static final Instant T = Instant.parse("2027-07-26T06:00:00Z");

    static Span span(int fromH, int toH) {
        return new Span(T.plus(Duration.ofHours(fromH)), T.plus(Duration.ofHours(toH)));
    }

    @Test
    void coverage_findsTheFirstUncoveredInstant() {
        var to = T.plus(Duration.ofDays(2));
        assertThat(Coverage.firstGap(List.of(span(-1, 24), span(24, 50)), T, to))
                .isEmpty();
        assertThat(Coverage.firstGap(List.of(span(12, 30), span(-2, 13)), T, to))
                .contains(T.plus(Duration.ofHours(30)));
        assertThat(Coverage.firstGap(List.of(span(1, 50)), T, to)).contains(T);
        assertThat(Coverage.firstGap(List.of(span(0, 10), span(11, 50)), T, to)).contains(T.plus(Duration.ofHours(10)));
        assertThat(Coverage.firstGap(List.of(), T, to)).contains(T);
    }

    @Test
    void hypercare_rotatesFourteenDays_andNeverPagesThePrimaryAsSecondary() {
        var start = LocalDate.parse("2027-07-26");
        var days = HypercareRotation.plan(start, List.of("a", "b", "c"), List.of("a", "b"), List.of("x"));
        assertThat(days).hasSize(14);
        assertThat(days.getFirst().date()).isEqualTo(start);
        assertThat(days.getLast().date()).isEqualTo(start.plusDays(13));
        assertThat(days).allSatisfy(d -> assertThat(d.primary()).isNotEqualTo(d.secondary()));
        assertThat(days.get(0).secondary()).isEqualTo("b"); // the turn gave "a" to both: the next secondary takes it
        assertThat(days).allSatisfy(d -> assertThat(d.business()).isEqualTo("x"));
        assertThatThrownBy(() -> HypercareRotation.plan(start, List.of("a"), List.of("a"), List.of("x")))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> HypercareRotation.plan(start, List.of(), List.of("a"), List.of("x")))
                .isInstanceOf(RuleViolation.class);
    }

    @Test
    void aLaunchRequest_needsASecondPerson_andLapses() {
        var r = LaunchRequest.open("R", "M", "alice", T, Duration.ofDays(1), " ", null, List.of());
        assertThat(r.override()).isFalse();
        assertThat(r.note()).isNull();
        assertThatThrownBy(() -> r.approve("alice", T.plusSeconds(60)))
                .isInstanceOf(Conflict.class)
                .hasMessage(LaunchRequest.SAME_PERSON);
        var approved = r.approve("bob", T.plusSeconds(60));
        assertThat(approved.state()).isEqualTo(LaunchRequest.State.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("bob");
        assertThatThrownBy(() -> approved.approve("carol", T.plusSeconds(120))).hasMessage(LaunchRequest.CLOSED);
        assertThat(r.lapsed(T.plus(Duration.ofHours(24)))).isTrue();
        assertThatThrownBy(() -> r.approve("bob", T.plus(Duration.ofHours(25)))).hasMessage(LaunchRequest.EXPIRED);
        assertThat(r.close("alice", null, T).state()).isEqualTo(LaunchRequest.State.WITHDRAWN);
        assertThat(r.close("bob", "no", T).state()).isEqualTo(LaunchRequest.State.REJECTED);
    }

    @Test
    void anOverride_needsAReasonOfTwentyCharacters() {
        assertThatThrownBy(
                        () -> LaunchRequest.open("R", "M", "a", T, Duration.ofHours(1), null, "too short", List.of()))
                .isInstanceOf(RuleViolation.class);
        var r = LaunchRequest.open(
                "R",
                "M",
                "a",
                T,
                Duration.ofHours(1),
                null,
                "Launch with the pentest report pending.",
                List.of("pentest"));
        assertThat(r.override()).isTrue();
        assertThat(r.blocking()).containsExactly("pentest");
    }
}
