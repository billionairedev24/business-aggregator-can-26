package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.StaffRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-90: the role → screen / action matrix is design 03's {@code ROLES} (plus the Data Table's {@code CAN}). */
class StaffRoleTest {

    @Test
    void screensPerRole_matchTheDesign() {
        assertThat(StaffRole.ADMIN.screens()).containsExactlyInAnyOrder(ConsoleScreen.values());
        assertThat(StaffRole.DISPATCH.screens())
                .containsExactlyInAnyOrder(
                        ConsoleScreen.OVERVIEW, ConsoleScreen.ORDERS, ConsoleScreen.DELIVERY, ConsoleScreen.SUPPORT);
        assertThat(StaffRole.ANALYST.screens())
                .containsExactlyInAnyOrder(ConsoleScreen.OVERVIEW, ConsoleScreen.REPORTS);
        // provinces, catalogue and API & webhooks are admin-only
        for (var role : List.of(
                StaffRole.TRUST_SAFETY, StaffRole.DISPATCH, StaffRole.FINANCE, StaffRole.SUPPORT, StaffRole.ANALYST)) {
            assertThat(role.opens(ConsoleScreen.REGIONS)).as(role.code()).isFalse();
            assertThat(role.opens(ConsoleScreen.TAXONOMY)).as(role.code()).isFalse();
            assertThat(role.opens(ConsoleScreen.API)).as(role.code()).isFalse();
            assertThat(role.opens(ConsoleScreen.PROFILE))
                    .as("profile is everyone's")
                    .isTrue();
            assertThat(role.opens(ConsoleScreen.ONCALL))
                    .as("on-call is everyone's")
                    .isTrue();
        }
    }

    @Test
    void onlyAdminsFlipProvinces_andAnalystsChangeNothing() {
        assertThat(StaffRole.ADMIN.allows(ConsoleAction.PROVINCE)).isTrue();
        assertThat(List.of(StaffRole.values()).stream().filter(r -> r.allows(ConsoleAction.PROVINCE)))
                .containsExactly(StaffRole.ADMIN);
        assertThat(StaffRole.ANALYST.actions()).isEmpty();
    }

    @Test
    void privacyRequests_belongToThePrivacyOfficerSupportLeadsAndAdmins() {
        assertThat(List.of(StaffRole.values()).stream().filter(r -> r.opens(ConsoleScreen.PRIVACY)))
                .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.PRIVACY, StaffRole.SUPPORT_LEAD);
        assertThat(List.of(StaffRole.values()).stream().filter(r -> r.allows(ConsoleAction.PRIVACY)))
                .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.PRIVACY, StaffRole.SUPPORT_LEAD);
        assertThat(StaffRole.SUPPORT.opens(ConsoleScreen.PRIVACY)).isFalse();
        assertThat(StaffRole.PRIVACY.actions()).containsExactly(ConsoleAction.PRIVACY);
        assertThat(StaffRole.fromCode("privacy")).contains(StaffRole.PRIVACY);
    }

    @Test
    void pilotOnboarding_isMerchantSuccesss_trustAndSafetyRead() {
        assertThat(List.of(StaffRole.values()).stream().filter(r -> r.allows(ConsoleAction.ONBOARD)))
                .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.MERCHANT_SUCCESS);
        assertThat(List.of(StaffRole.values()).stream().filter(r -> r.opens(ConsoleScreen.PILOT)))
                .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.MERCHANT_SUCCESS, StaffRole.TRUST_SAFETY);
        assertThat(StaffRole.fromCode("merchant_success")).contains(StaffRole.MERCHANT_SUCCESS);
    }

    @Test
    void heldRoles_comeFromTheTokensPlatformRoles() {
        assertThat(StaffRole.held(List.of("staff", "TRUST_SAFETY", "finance", "partner")))
                .containsExactlyInAnyOrder(StaffRole.TRUST_SAFETY, StaffRole.FINANCE);
        assertThat(StaffRole.fromCode("trust_safety")).contains(StaffRole.TRUST_SAFETY);
    }
}
