package ca.northline.region.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.region.api.AgeClass;
import ca.northline.region.api.AgeRules;
import ca.northline.region.api.AgeRules.AgeRule;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The age rule's windows and a cart's requirement (strictest class; a class with no rule refuses the cart). */
class AgeRulesTest {

    static final AgeRule ALCOHOL = new AgeRule(
            "XA", AgeClass.ALCOHOL, 18, true, true, LocalTime.of(10, 0), LocalTime.of(2, 0), null, null, "test", false);
    static final AgeRule TOBACCO =
            new AgeRule("XA", AgeClass.TOBACCO, 21, true, false, null, null, null, null, "test", false);

    static final AgeRules RULES = new AgeRules() {
        final Map<AgeClass, AgeRule> rows = Map.of(AgeClass.ALCOHOL, ALCOHOL, AgeClass.TOBACCO, TOBACCO);

        @Override
        public Optional<AgeRule> rule(@Nullable String province, AgeClass ageClass) {
            return "XA".equals(province) ? Optional.ofNullable(rows.get(ageClass)) : Optional.empty();
        }

        @Override
        public List<AgeRule> all() {
            return List.copyOf(rows.values());
        }

        @Override
        public int highestMinimumAge() {
            return 21;
        }
    };

    @Test
    void aWindowPastMidnightAllowsLateEveningAndEarlyMorning() {
        assertThat(ALCOHOL.allows(false, LocalTime.of(9, 59))).isFalse();
        assertThat(ALCOHOL.allows(false, LocalTime.of(10, 0))).isTrue();
        assertThat(ALCOHOL.allows(false, LocalTime.of(23, 30))).isTrue();
        assertThat(ALCOHOL.allows(false, LocalTime.of(1, 59))).isTrue();
        assertThat(ALCOHOL.allows(false, LocalTime.of(2, 0))).isFalse();
        // no pickup window configured: any time
        assertThat(ALCOHOL.allows(true, LocalTime.of(5, 0))).isTrue();
    }

    @Test
    void pickupOrDeliveryCanBeRuledOutPerClass() {
        assertThat(TOBACCO.allows(false, LocalTime.NOON)).isTrue();
        assertThat(TOBACCO.allows(true, LocalTime.NOON)).isFalse();
    }

    @Test
    void theStrictestClassInTheCartSetsTheAge() {
        var r = RULES.requirement("XA", List.of(AgeClass.ALCOHOL, AgeClass.TOBACCO))
                .orElseThrow();
        assertThat(r.minimumAge()).isEqualTo(21);
        assertThat(r.classes()).containsExactly(AgeClass.ALCOHOL, AgeClass.TOBACCO);
        assertThat(r.allows(false, LocalTime.of(5, 0))).isFalse(); // alcohol's window
        assertThat(RULES.requirement("XA", List.of(AgeClass.ALCOHOL))
                        .orElseThrow()
                        .minimumAge())
                .isEqualTo(18);
    }

    @Test
    void aClassWithoutARuleInTheProvinceCantBeSoldThere() {
        assertThat(RULES.requirement("XA", List.of(AgeClass.CANNABIS))).isEmpty();
        assertThat(RULES.requirement("XB", List.of(AgeClass.ALCOHOL))).isEmpty();
        assertThat(RULES.requirement(null, List.of(AgeClass.ALCOHOL))).isEmpty();
    }

    @Test
    void classesAreCodes() {
        assertThat(AgeClass.of("tobacco")).contains(AgeClass.TOBACCO);
        assertThat(AgeClass.of("beer")).isEmpty();
        assertThat(AgeClass.of(null)).isEmpty();
    }
}
