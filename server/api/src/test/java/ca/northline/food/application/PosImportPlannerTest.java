package ca.northline.food.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.food.application.PosMenuSource.PosGroup;
import ca.northline.food.application.PosMenuSource.PosOption;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.PickRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-36: POS modifier rules → the builder's pick rules, and what the planner refuses. */
class PosImportPlannerTest {

    static List<PosOption> options(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new PosOption("o" + i, "Option " + i, 0))
                .toList();
    }

    @Test
    void minAndMaxBecomeTheBuildersRule() {
        var exactly = PosImportPlanner.rule(new PosGroup("g", "Size", 1, 1, options(2)));
        assertThat(exactly.rule()).isEqualTo(PickRule.EXACTLY);
        assertThat(exactly.count()).isEqualTo(1);
        assertThat(exactly.required()).isTrue();
        var atLeast = PosImportPlanner.rule(new PosGroup("g", "Sides", 2, 4, options(5)));
        assertThat(atLeast.rule()).isEqualTo(PickRule.AT_LEAST);
        assertThat(atLeast.count()).isEqualTo(2);
        var upTo = PosImportPlanner.rule(new PosGroup("g", "Extras", 0, null, options(3)));
        assertThat(upTo.rule()).isEqualTo(PickRule.UP_TO);
        assertThat(upTo.count()).isEqualTo(3);
        assertThat(upTo.required()).isFalse();
        // never more than the options, never more than 20
        assertThat(PosImportPlanner.rule(new PosGroup("g", "X", 5, 5, options(2)))
                        .count())
                .isEqualTo(2);
        assertThat(PosImportPlanner.rule(new PosGroup("g", "X", 0, 50, options(30)))
                        .count())
                .isEqualTo(20);
    }

    @Test
    void groupsWithoutOptionsOrWithOutOfRangePricesAreProblems() {
        assertThat(PosImportPlanner.problem(new PosGroup("g", "Empty", 0, null, List.of())))
                .isEqualTo(KitchenMessages.POS_NO_OPTIONS);
        assertThat(PosImportPlanner.problem(
                        new PosGroup("g", "Big", 0, null, List.of(new PosOption("o", "Lobster", 12_000)))))
                .isEqualTo(KitchenMessages.POS_OPTION_PRICE);
        assertThat(PosImportPlanner.problem(new PosGroup("g", "Ok", 0, null, options(1))))
                .isNull();
    }

    @Test
    void namesAreCutToTheBuildersLimits() {
        assertThat(PosImportPlanner.itemName("x".repeat(120))).hasSize(80);
        assertThat(PosImportPlanner.groupName("  ")).isEqualTo("Options");
        assertThat(PosImportPlanner.description("  a \n b  ")).isEqualTo("a b");
        assertThat(PosImportPlanner.description(" ")).isNull();
    }
}
