package ca.northline.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.RuleViolation;
import org.junit.jupiter.api.Test;

/** S-57: the tracking stage a customer sees and the tip rule. */
class FoodOrderRulesTest {

    @Test
    void theStageFollowsTheKitchenTicketBeforeTheOrderCatchesUp() {
        assertThat(FoodOrderRules.stage("placed", null, "delivery")).isEqualTo("paid");
        assertThat(FoodOrderRules.stage("placed", "cooking", "delivery")).isEqualTo("cooking");
        assertThat(FoodOrderRules.stage("accepted", null, "delivery")).isEqualTo("cooking");
        assertThat(FoodOrderRules.stage("accepted", "ready", "delivery")).isEqualTo("ready");
        assertThat(FoodOrderRules.stage("ready", "handed_off", "delivery")).isEqualTo("on_the_way");
        assertThat(FoodOrderRules.stage("ready", "handed_off", "pickup")).isEqualTo("delivered");
        assertThat(FoodOrderRules.stage("picked_up", "handed_off", "delivery")).isEqualTo("on_the_way");
        assertThat(FoodOrderRules.stage("delivered", "handed_off", "delivery")).isEqualTo("delivered");
        assertThat(FoodOrderRules.stage("refunded", "cooking", "delivery")).isEqualTo("refunded");
    }

    @Test
    void tipsAreAnAmountOrAPercentWithinLimits() {
        assertThat(FoodOrderRules.tipCents("none", 0, 3800)).isZero();
        assertThat(FoodOrderRules.tipCents("amount", 400, 3800)).isEqualTo(400);
        assertThat(FoodOrderRules.tipCents("percent", 15, 3800)).isEqualTo(570);
        assertThatThrownBy(() -> FoodOrderRules.tipCents("amount", 10_001, 3800))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> FoodOrderRules.tipCents("percent", 31, 3800)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> FoodOrderRules.tipCents("gift", 1, 3800)).isInstanceOf(RuleViolation.class);
    }
}
