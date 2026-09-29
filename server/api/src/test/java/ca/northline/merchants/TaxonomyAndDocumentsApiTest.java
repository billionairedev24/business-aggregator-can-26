package ca.northline.merchants;

import static ca.northline.merchants.OnboardingApiTest.err;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** {@code GET /api/v1/onboarding/taxonomy} and merchant documents. */
class TaxonomyAndDocumentsApiTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void seed() {
        new CategorySeeder(dataSource).seed();
    }

    @Test
    void providerTaxonomyIsGroupedInSeedOrder_withRegulatorsAndLimit() throws Exception {
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "provider").with(TestJwt.customer(data.user("U"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.groups[0].id").value("service.automotive"))
                .andExpect(jsonPath("$.groups[0].name").value("Automotive"))
                .andExpect(jsonPath("$.groups[0].note").value("AMVIC licence checked"))
                .andExpect(jsonPath("$.groups[0].items[0].id").value("service.automotive.mobile-mechanic"))
                .andExpect(jsonPath("$.groups[0].items[0].name").value("Mobile mechanic"))
                .andExpect(jsonPath("$.groups[0].items[0].regulator").value("AMVIC"))
                .andExpect(jsonPath("$.groups[1].name").value("Home trades"))
                .andExpect(jsonPath("$.groups[*].id", everyItem(startsWith("service."))));
    }

    @Test
    void limitsAndRootsPerType() throws Exception {
        var user = TestJwt.customer(data.user("U"));
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "seller").with(user))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.groups[0].name").value("Food & grocery"));
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "kitchen").with(user))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.groups[*].name", hasItem("Format")));
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "both").with(user))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.groups[0].id").value("service.automotive"))
                .andExpect(jsonPath("$.groups[*].id", hasItem("shop.hardware-and-auto")));
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "farm").with(user))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("type", "Pick provider, seller, kitchen or both."));
        mvc.perform(get("/api/v1/onboarding/taxonomy").param("type", "seller")).andExpect(status().isUnauthorized());
    }

    @Test
    void documentsUploadAndReadBack_membersOnly() throws Exception {
        var owner = data.user("Owner");
        var flow = new OnboardingFlow(mvc);
        var id = flow.start(owner, "provider");
        var doc = flow.upload(id, owner, "verification");

        mvc.perform(get("/api/v1/merchants/{id}/onboarding/documents/{doc}", id, doc)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(OnboardingFlow.PDF.getBytes()));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding/documents/{doc}", id, doc)
                        .with(TestJwt.member(data.user("X"))))
                .andExpect(status().isForbidden());
        var other = flow.start(owner, "provider");
        mvc.perform(get("/api/v1/merchants/{id}/onboarding/documents/{doc}", other, doc)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentTypesAreChecked() throws Exception {
        var owner = data.user("Owner");
        var id = new OnboardingFlow(mvc).start(owner, "provider");
        mvc.perform(multipart("/api/v1/merchants/{id}/onboarding/documents", id)
                        .file(new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[] {1, 2}))
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("file", "Upload a PDF, PNG or JPEG under 10 MB."));
        mvc.perform(multipart("/api/v1/merchants/{id}/onboarding/documents", id)
                        .file(new MockMultipartFile("file", "logo.pdf", "application/pdf", new byte[] {1}))
                        .param("purpose", "logo")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("file", "Upload an SVG or PNG under 10 MB."));
        mvc.perform(multipart("/api/v1/merchants/{id}/onboarding/documents", id).with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(err("file", "Choose a file to upload."));
    }
}
