package ca.northline.studio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.studio.application.NavBadges;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code GET /api/v1/merchants/{id}/nav-badges} (merging itself: {@code NavBadgeServiceTest}). */
class NavBadgesTest extends IntegrationTest {

    @Autowired
    NavBadges navBadges;

    @Test
    void membersGetTheMergedMapOfEveryContributor() throws Exception {
        var biz = data.business(MerchantRole.BOOKKEEPER);
        var expected = navBadges.of(new NavBadgeContributor.Context(
                biz.merchantId(), biz.userId(), MerchantRole.BOOKKEEPER, Locale.ENGLISH));
        var json = new StringBuilder("{");
        expected.forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append("\"%s\":\"%s\"".formatted(k, v)));
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", biz.merchantId())
                        .header("Accept-Language", "en-CA")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(content().json(json.append("}").toString()));
    }

    @Test
    void nonMembersAndSingleFactorAreForbidden() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", biz.merchantId()).with(TestJwt.member(data.user("X"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }
}
