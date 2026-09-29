package ca.northline.studio.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NavBadgeServiceTest {

    static final NavBadgeContributor.Context FRENCH =
            new NavBadgeContributor.Context("M1", "U1", MerchantRole.OWNER, Locale.CANADA_FRENCH);

    @Test
    void mergesContributors_dropsBlanks_skipsFailures_passesTheLocale() {
        NavBadgeContributor orders = c -> Map.of("orders", c.french() ? "4 à emballer" : "4 to pack", "help", " ");
        NavBadgeContributor broken = _ -> {
            throw new IllegalStateException("boom");
        };
        NavBadgeContributor messages = _ -> Map.of("messages", "3", "orders", "other");

        var badges = new NavBadgeService(List.of(orders, broken, messages)).of(FRENCH);

        assertThat(badges).containsExactly(Map.entry("messages", "3"), Map.entry("orders", "4 à emballer"));
    }

    @Test
    void noContributorsMeansNoBadges() {
        assertThat(new NavBadgeService(List.of()).of(FRENCH)).isEmpty();
    }
}
