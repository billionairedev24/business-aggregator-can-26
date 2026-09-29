package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import java.time.Instant;

/**
 * An uploaded file (legal document, verification evidence, storefront logo) — {@code merchants.documents}. The id is
 * the "media id" that {@code legal_details.*_doc}, {@code verifications.document_media_id} and
 * {@code storefronts.logo_media_id} point at.
 */
public record Document(
        String id,
        String merchantId,
        Purpose purpose,
        String fileName,
        String contentType,
        long sizeBytes,
        String storageKey,
        String uploadedBy,
        Instant createdAt) {

    /** {@code merchants.documents.purpose}. */
    public enum Purpose implements CodedEnum {
        LEGAL,
        VERIFICATION,
        LOGO
    }
}
