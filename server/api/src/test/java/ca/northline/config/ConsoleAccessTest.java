package ca.northline.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S-20: staff (console) tokens need {@code acr=mfa} as well as the staff role, like business tokens. No console
 * endpoint exists yet (E-8), so "allowed" is the 404 behind the security chain.
 */
class ConsoleAccessTest extends IntegrationTest {

    private static RequestPostProcessor token(List<String> roles, @Nullable String acr) {
        return jwt().jwt(j -> {
                    j.subject("01J9ZD3V0000000000000STAFF")
                            .claim("scope", "openid profile console")
                            .claim("roles", roles);
                    if (acr != null) {
                        j.claim("acr", acr);
                    }
                })
                .authorities(NorthlineJwtConverter::authorities);
    }

    @Test
    void staffWithASecondFactor_passes() throws Exception {
        mvc.perform(get("/api/v1/console/merchants").with(token(List.of("staff"), "mfa")))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffWithoutASecondFactor_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/console/merchants").with(token(List.of("staff"), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void aSecondFactorWithoutTheStaffRole_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/console/merchants").with(token(List.of(), "mfa")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
