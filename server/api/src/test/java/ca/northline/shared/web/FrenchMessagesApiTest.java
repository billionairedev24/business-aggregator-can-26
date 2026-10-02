package ca.northline.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.PlaceNames;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** S-40: 422 messages and 403/409 details follow {@code Accept-Language}; English stays the spec's exact text. */
class FrenchMessagesApiTest extends IntegrationTest {

    @Autowired
    PlaceNames placeNames;

    @ParameterizedTest(name = "[{index}] {0} ''{1}''")
    @CsvSource(
            delimiter = '|',
            value = {
                "fr-CA                  | ''    | required | Entrez le nom que verront les clients.",
                "fr                     | ' A ' | length   | Au moins 2 caractères.", // RuleViolation (DisplayName)
                "fr-CA,fr;q=0.9,en;q=0.8 | A    | length   | Au moins 2 caractères.", // Bean Validation @Size
                "en-CA                  | ''    | required | Enter the name customers will see.",
                "en-CA,fr-CA;q=0.9      | A     | length   | At least 2 characters.",
                "'*'                    | ''    | required | Enter the name customers will see.",
            })
    void validationMessagesFollowAcceptLanguage(String language, String name, String rule, String message)
            throws Exception {
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patchName(biz.merchantId(), name)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].rule").value(rule))
                .andExpect(jsonPath("$.errors[0].message").value(message));
    }

    @Test
    void withoutAcceptLanguageTheSpecEnglishIsSent() throws Exception {
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patchName(biz.merchantId(), "x".repeat(81)).with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("At most 80 characters."));
    }

    @Test
    void forbiddenDetailIsFrenchToo() throws Exception {
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patchName(biz.merchantId(), "Hijacked")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA")
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"))
                .andExpect(jsonPath("$.detail")
                        .value("L’accès Entreprise exige un deuxième facteur. Reconnectez-vous avec votre clé d’accès"
                                + " ou votre application d’authentification."));
    }

    /** S-116: every ProblemDetail — title and detail, 404s included — follows Accept-Language too. */
    @Test
    void problemTitlesAndNotFoundDetailsAreFrench() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var missing = "01J9ZD3V0000000000000NONE1";

        mvc.perform(get("/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), missing)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Introuvable"))
                .andExpect(jsonPath("$.detail").value("Aucune annonce avec l’identifiant " + missing))
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(get("/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), missing)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("No listing with id " + missing));
        mvc.perform(patchName(biz.merchantId(), "Hijacked")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Accès refusé"));
    }

    @Test
    void placeNamesComeFromTheRegionModel() {
        assertThat(placeNames.french("Nova Scotia", false)).hasValue("Nouvelle-Écosse");
        assertThat(placeNames.french("Quebec", true)).hasValue("au Québec");
        assertThat(placeNames.french("Atlantis", false)).isEmpty();
    }

    private static MockHttpServletRequestBuilder patchName(String merchantId, String name) {
        return patch("/api/v1/merchants/{id}", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\"}".formatted(name));
    }
}
