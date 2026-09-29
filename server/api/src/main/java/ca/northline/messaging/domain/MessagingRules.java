package ca.northline.messaging.domain;

import java.util.Set;

/**
 * Limits and exact validation messages for Messages and Help (validation-rules.md has no section for them; the client
 * mirrors these in {@code features/messages/validation.ts} and {@code features/help/validation.ts}).
 */
public final class MessagingRules {

    private MessagingRules() {}

    public static final int MESSAGE_MAX = 2000;
    public static final int FILES_MAX = 5;
    public static final long FILE_MAX_BYTES = 10L * 1024 * 1024;
    public static final Set<String> FILE_TYPES =
            Set.of("image/jpeg", "image/png", "image/heic", "image/heif", "application/pdf");
    public static final int CASE_BODY_MAX = 4000;
    public static final int SUBJECT_MAX = 80;

    public static final String MESSAGE_REQUIRED = "Write a message or attach a file.";
    public static final String MESSAGE_TOO_LONG = "Keep messages under 2,000 characters.";
    public static final String TOO_MANY_FILES = "Attach up to 5 files.";
    public static final String FILE_GONE = "That file is no longer available. Attach it again.";
    public static final String FILE_REQUIRED = "Choose a file to attach.";
    public static final String FILE_TYPE = "Attach JPG, PNG, HEIC or PDF files.";
    public static final String FILE_TOO_LARGE = "Files can be up to 10 MB.";

    public static final String TOPIC_REQUIRED = "Choose a topic.";
    public static final String CASE_BODY_REQUIRED = "Tell us what's happening.";
    public static final String CASE_BODY_TOO_LONG = "Keep it under 4,000 characters.";
    public static final String CHANNEL_REQUIRED = "Choose how we should reach you.";
    public static final String RELATED_INVALID = "Pick a record from the list.";

    public static final String TOPIC_CODES =
            "verification|payouts|refunds|appointments|listings|account|api|safety|other";
    public static final String CHANNEL_CODES = "chat|call|email";
    public static final String REF_TYPE_CODES = "booking|order|payout|dispute|document";
}
