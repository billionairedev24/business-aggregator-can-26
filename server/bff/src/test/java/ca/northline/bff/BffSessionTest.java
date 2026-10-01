package ca.northline.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.bff.config.NextRedirect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The BFF session contract: GET /bff/session, POST /bff/logout, GET /bff/login, CSRF and 401s. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BffSessionTest {

    @Autowired
    MockMvc mvc;

    @Test
    void session_signedOut_is401_andSetsTheCsrfCookie() throws Exception {
        mvc.perform(get("/bff/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void session_signedIn_returnsTheUserFromTheIdToken() throws Exception {
        mvc.perform(get("/bff/session")
                        .with(oidcLogin()
                                .idToken(t -> t.subject("01J9ZD3V00000000000000RAV1")
                                        .claim("given_name", "Ravi")
                                        .claim("family_name", "Sandhu")
                                        .claim("email", "ravi.sandhu@example.com")
                                        .claim("phone_number", "+14035550148")
                                        .claim("locale", "en-CA")
                                        .claim("member_since", "2026-01-05")
                                        .claim("acr", "mfa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("01J9ZD3V00000000000000RAV1"))
                .andExpect(jsonPath("$.user.firstName").value("Ravi"))
                .andExpect(jsonPath("$.user.lastName").value("Sandhu"))
                .andExpect(jsonPath("$.user.email").value("ravi.sandhu@example.com"))
                .andExpect(jsonPath("$.user.phone").value("+14035550148"))
                .andExpect(jsonPath("$.user.initials").value("RS"))
                .andExpect(jsonPath("$.user.locale").value("en-CA"))
                .andExpect(jsonPath("$.user.memberSince").value("2026-01-05"))
                .andExpect(jsonPath("$.acr").value("mfa"));
    }

    @Test
    void session_withoutSecondFactor_hasNoAcr() throws Exception {
        mvc.perform(get("/bff/session")
                        .with(oidcLogin().idToken(t -> t.subject("u1").claim("given_name", "Jo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").doesNotExist());
    }

    @Test
    void logout_needsCsrf() throws Exception {
        mvc.perform(post("/bff/logout").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    void logout_withTheXsrfCookieAndHeader_invalidatesTheSession_andAnswers204() throws Exception {
        var session = new MockHttpSession();
        var xsrf = mvc.perform(get("/bff/session").session(session))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        mvc.perform(post("/bff/logout")
                        .session(session)
                        .with(oidcLogin())
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void login_remembersNext_andStartsPkceAuthorization() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/bff/login")
                        .param("next", "/b/01J9ZD3V00000000000000PWM1/orders")
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/studio"));
        assertThat(session.getAttribute(NextRedirect.SESSION_KEY)).isEqualTo("/b/01J9ZD3V00000000000000PWM1/orders");

        mvc.perform(get("/oauth2/authorization/studio").session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("http://localhost:9000/oauth2/authorize?")))
                .andExpect(header().string("Location", containsString("client_id=studio-bff")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string("Location", containsString("scope=openid%20profile%20merchant")));
    }

    @Test
    void login_rejectsOffSiteNext() {
        assertThat(NextRedirect.safe("//evil.example/x")).isEqualTo("/");
        assertThat(NextRedirect.safe("https://evil.example")).isEqualTo("/");
        assertThat(NextRedirect.safe("/\\evil.example")).isEqualTo("/");
        assertThat(NextRedirect.safe(null)).isEqualTo("/");
        assertThat(NextRedirect.safe("/onboarding")).isEqualTo("/onboarding");
        // S-20: browsers drop tabs and newlines from URLs, so these would become //evil.example
        assertThat(NextRedirect.safe("/\t/evil.example")).isEqualTo("/");
        assertThat(NextRedirect.safe("/\n/evil.example")).isEqualTo("/");
        assertThat(NextRedirect.safe("/\r\n/evil.example")).isEqualTo("/");
        assertThat(NextRedirect.safe("/a\u007fb")).isEqualTo("/");
    }

    @Test
    void api_signedOut_is401_notARedirect() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    // ---- S-125: the session API as OpenAPI 3.1 under /bff ----------------------------------------------------------

    @Test
    void openApi_committedSpecMatchesTheCode() throws Exception {
        ca.northline.openapi.OpenApiSnapshot.verify(mvc, "/bff/v3/api-docs", "bff", java.util.List.of("internal"));
    }

    @Test
    void openApi_viewersRenderUnderBffWithTheirCsp_andTheSessionApiKeepsDefaultSrcNone() throws Exception {
        var landing = mvc.perform(get("/bff/docs")).andReturn().getResponse();
        assertThat(landing.getStatus()).isEqualTo(200);
        assertThat(landing.getHeader("Content-Security-Policy")).contains("script-src 'self'");
        assertThat(landing.getContentAsString())
                .contains("/bff/swagger-ui.html?urls.primaryName=internal")
                .contains("/bff/docs/scalar")
                .contains("/bff/docs/redoc?group=internal");
        for (var path : java.util.List.of(
                "/bff/docs/redoc",
                "/bff/docs/redoc/redoc.standalone.js",
                "/bff/v3/api-docs/swagger-config",
                "/bff/v3/api-docs/internal")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as(path)
                    .isEqualTo(200);
        }
        // Swagger UI: its entry point under /bff (the webjar's own files are checked in the api's and auth's tests;
        // serving
        // them here reliably triggers a JDK HttpClient race in ConsumerBffTest's relay — see DECISIONS, S-125).
        assertThat(mvc.perform(get("/bff/swagger-ui.html"))
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl())
                .contains("/bff/swagger-ui/index.html");
        var spec = mvc.perform(get("/bff/v3/api-docs/internal"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(spec).contains("\"/bff/session\"", "\"/bff/logout\"", "\"NL_STUDIO\"", "X-XSRF-TOKEN");
        mvc.perform(get("/bff/session"))
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.startsWith("default-src 'none'")));
    }

    @Test
    void openApi_productionPublishesNothing() throws Exception {
        var prod = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("prod", new org.springframework.core.io.ClassPathResource("application-prod.yml"))
                .getFirst();
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("scalar.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("northline.docs.enabled")).isEqualTo(false);
    }
}
