package ca.northline.food.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.food.domain.PickCheck.Group;
import ca.northline.food.domain.PickCheck.Option;
import ca.northline.shared.RuleViolation.Violation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** S-57: the customer-side pick rules and the food fees. */
class FoodRulesTest {

    private static final Group SIZE = new Group(
            "g1",
            "Size",
            PickRule.EXACTLY,
            1,
            true,
            Set.of(),
            List.of(new Option("reg", "Regular", false), new Option("lg", "Large", false)));
    private static final Group EXTRAS = new Group(
            "g2",
            "Extras",
            PickRule.UP_TO,
            2,
            false,
            Set.of(),
            List.of(
                    new Option("brisket", "Brisket", false),
                    new Option("egg", "Egg", true),
                    new Option("tendon", "Tendon", false)));
    /** Only shown once "Large" is picked. */
    private static final Group NOODLES = new Group(
            "g3",
            "Noodles",
            PickRule.AT_LEAST,
            1,
            true,
            Set.of("lg"),
            List.of(new Option("rice", "Rice noodles", false), new Option("egg-n", "Egg noodles", false)));

    private static List<String> messages(List<String> chosen) {
        return PickCheck.check("items[0].optionIds", List.of(SIZE, EXTRAS, NOODLES), chosen).stream()
                .map(Violation::message)
                .toList();
    }

    @Test
    void pickRules() {
        assertThat(messages(List.of("reg"))).isEmpty();
        assertThat(messages(List.of())).containsExactly("Pick 1 for Size.");
        assertThat(messages(List.of("reg", "lg"))).contains("Pick 1 for Size.");
        assertThat(messages(List.of("reg", "brisket", "tendon"))).isEmpty();
        assertThat(messages(List.of("reg", "brisket", "tendon", "egg")))
                .containsExactly("Pick up to 2 for Extras.", "Egg is sold out.");
    }

    @Test
    void nestedGroupsCountOnlyWhenShown() {
        assertThat(messages(List.of("lg"))).containsExactly("Pick at least 1 for Noodles.");
        assertThat(messages(List.of("lg", "rice", "egg-n"))).isEmpty();
        // a hidden group's option is not a valid choice
        assertThat(messages(List.of("reg", "rice")))
                .containsExactly("That choice isn't on this dish any more. Open it again.");
        assertThat(messages(List.of("reg", "reg"))).containsExactly("Choose each option once.");
    }

    @Test
    void feesByDistanceAndPriceLevel() {
        assertThat(FoodFees.deliveryFeeCents(0.8)).isEqualTo(199);
        assertThat(FoodFees.deliveryFeeCents(1.9)).isEqualTo(299);
        assertThat(FoodFees.deliveryFeeCents(4)).isEqualTo(399);
        assertThat(FoodFees.deliveryFeeCents(4.1)).isEqualTo(499);
        assertThat(FoodFees.deliveryFeeCents(7.5)).isEqualTo(599);
        assertThat(FoodFees.serviceFeeCents(3800)).isEqualTo(304);
        assertThat(FoodFees.rideMinutes(2.4)).isEqualTo(13);
        assertThat(FoodFees.priceLevel(1100)).isEqualTo("$");
        assertThat(FoodFees.priceLevel(1800)).isEqualTo("$$");
        assertThat(FoodFees.priceLevel(3000)).isEqualTo("$$$");
        // Beltline → Bridgeland, Calgary
        assertThat(FoodFees.km(51.0380, -114.0870, 51.0530, -114.0380)).isBetween(3.5, 4.0);
    }
}
