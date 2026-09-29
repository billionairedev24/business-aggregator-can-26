package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.application.DocumentStorage;
import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.storage.VirusScanner;
import ca.northline.support.IntegrationTest;
import ca.northline.support.ObjectStorageContainers;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-10 end to end: the api with {@code STORAGE_PROVIDER=s3} against RustFS. Uploads through real endpoints land in the
 * bucket under {@code <module>/<merchantId>/<ulid>.<ext>}, read back through the same endpoints (merchant checks
 * unchanged), and a {@link VirusScanner} bean rejects an upload before it is stored.
 */
@Import(DocumentsInObjectStorageTest.Scanner.class)
class DocumentsInObjectStorageTest extends IntegrationTest {

    static final String BUCKET = "s10-api";

    @BeforeAll
    static void bucket() {
        ObjectStorageContainers.createS3Bucket(BUCKET);
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("northline.storage.provider", () -> "s3");
        registry.add("northline.storage.bucket", () -> BUCKET);
        registry.add("northline.storage.endpoint", ObjectStorageContainers::s3Endpoint);
        registry.add("northline.storage.access-key", () -> ObjectStorageContainers.S3_ACCESS_KEY);
        registry.add("northline.storage.secret-key", () -> ObjectStorageContainers.S3_SECRET_KEY);
        registry.add("northline.storage.path-style", () -> "true");
    }

    /** Stands in for a real scanner: anything starting with the EICAR test signature is "infected". */
    @TestConfiguration(proxyBeanMethods = false)
    static class Scanner {
        @Bean
        VirusScanner eicarScanner() {
            return (_, _, bytes) -> new String(bytes, StandardCharsets.US_ASCII).startsWith("X5O!P%@AP")
                    ? new VirusScanner.Verdict.Infected("EICAR-Test-File")
                    : new VirusScanner.Verdict.Clean();
        }
    }

    @Autowired
    DocumentStorage documentStorage;

    @Autowired
    AttachmentStorage attachmentStorage;

    @Test
    void theObjectStoreAdaptersReplaceTheLocalFakes() {
        assertThat(documentStorage.getClass().getSimpleName()).isEqualTo("ObjectStoreDocumentStorage");
        assertThat(attachmentStorage.getClass().getSimpleName()).isEqualTo("ObjectStoreAttachmentStorage");
    }

    @Test
    void onboardingDocumentLandsInTheBucketAndReadsBack() throws Exception {
        var owner = data.user("Owner");
        var flow = new OnboardingFlow(mvc);
        var merchantId = flow.start(owner, "provider");
        var documentId = flow.upload(merchantId, owner, "verification");

        try (var s3 = ObjectStorageContainers.s3Client()) {
            var head =
                    s3.headObject(b -> b.bucket(BUCKET).key("merchants/%s/%s.pdf".formatted(merchantId, documentId)));
            assertThat(head.contentType()).isEqualTo("application/pdf");
            assertThat(head.contentLength()).isEqualTo(OnboardingFlow.PDF.length());
        }
        mvc.perform(get("/api/v1/merchants/{id}/onboarding/documents/{doc}", merchantId, documentId)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(OnboardingFlow.PDF.getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding/documents/{doc}", merchantId, documentId)
                        .with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void messageAttachmentLandsInTheBucket_andAnInfectedFileIsRejected() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        var body = mvc.perform(multipart("/api/v1/merchants/{m}/message-attachments", biz.merchantId())
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(body, "$.id");

        try (var s3 = ObjectStorageContainers.s3Client()) {
            var stored = s3.getObjectAsBytes(
                    b -> b.bucket(BUCKET).key("messaging/%s/%s.png".formatted(biz.merchantId(), id)));
            assertThat(stored.asByteArray()).isEqualTo(png);
            assertThat(stored.response().contentType()).isEqualTo("image/png");
        }
        mvc.perform(get("/api/v1/merchants/{m}/message-attachments/{id}", biz.merchantId(), id)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png));

        var eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(StandardCharsets.US_ASCII);
        var pdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf", eicar);
        mvc.perform(multipart("/api/v1/merchants/{id}/onboarding/documents", biz.merchantId())
                        .file(pdf)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].rule").value("virus"))
                .andExpect(jsonPath("$.errors[0].message").value("This file can't be accepted. Try a different file."));
        try (var s3 = ObjectStorageContainers.s3Client()) {
            assertThat(s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("merchants/" + biz.merchantId() + "/"))
                            .contents())
                    .isEmpty();
        }
    }
}
