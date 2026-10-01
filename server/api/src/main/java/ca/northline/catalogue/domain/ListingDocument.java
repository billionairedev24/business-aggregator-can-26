package ca.northline.catalogue.domain;

import java.time.Instant;

/**
 * S-65: a spec sheet or an invoice (authenticity) a seller attached to a listing on the editor's Compliance tab, for
 * vetting. Private to the business and Northline staff; never shown to customers.
 */
public record ListingDocument(
        String id,
        String merchantId,
        String listingId,
        DocumentPurpose purpose,
        String fileName,
        String contentType,
        int byteSize,
        String storageKey,
        String uploadedBy,
        Instant createdAt) {}
