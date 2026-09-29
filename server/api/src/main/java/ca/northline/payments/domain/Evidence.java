package ca.northline.payments.domain;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One piece of dispute evidence (design 02: "Photos attached · 12", "Report PDF", "GPS check-in 1:58 pm"). Files live in
 * evidence storage under {@code storageKey}; GPS check-ins and job reports come from the job record.
 *
 * @param by {@code merchant} | {@code customer}
 */
public record Evidence(
        String id,
        Kind kind,
        String name,
        @Nullable String contentType,
        long size,
        String by,
        Instant at,
        @Nullable String storageKey) {

    public enum Kind implements CodedEnum {
        PHOTO,
        REPORT,
        GPS,
        DOCUMENT
    }

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_FILES = 20;

    /** Classifies an upload by its content type; empty when the type isn't accepted. */
    public static java.util.Optional<Kind> kindOf(@Nullable String contentType) {
        if (contentType == null) {
            return java.util.Optional.empty();
        }
        return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg", "image/png", "image/heic", "image/heif" -> java.util.Optional.of(Kind.PHOTO);
            case "application/pdf" -> java.util.Optional.of(Kind.DOCUMENT);
            default -> java.util.Optional.empty();
        };
    }
}
