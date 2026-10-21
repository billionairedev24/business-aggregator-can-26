package ca.northline.standing.domain;

import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** S-156: what an appeal, its files and its decision may hold, with the exact messages (fr in the catalogue). */
public final class AppealRules {

    /** Design copy: "You can appeal within 14 days". */
    public static final Duration WINDOW = Duration.ofDays(14);

    public static final int STATEMENT_MAX = 2000;
    public static final int REASON_MAX = 1000;
    public static final int FILES_MAX = 5;
    public static final long FILE_MAX_BYTES = 10L * 1024 * 1024;

    public static final String SUBJECT_REQUIRED = "Choose what you're appealing.";
    public static final String STATEMENT_REQUIRED = "Tell us why the decision should change.";
    public static final String STATEMENT_TOO_LONG = "Keep your appeal under 2,000 characters.";
    public static final String FILE_REQUIRED = "Choose a file to upload.";
    public static final String FILE_TYPE = "Upload a photo (JPG, PNG, HEIC) or a PDF.";
    public static final String FILE_SIZE = "Files can be up to 10 MB.";
    public static final String FILE_COUNT = "You can attach up to 5 files.";
    public static final String OUTCOME_REQUIRED = "Choose grant or deny.";
    public static final String REASON_REQUIRED = "Give the business a reason.";
    public static final String REASON_TOO_LONG = "Keep the reason under 1,000 characters.";

    public static final String ALREADY_APPEALED = "This was already appealed — each decision can be appealed once.";
    public static final String NOT_APPEALABLE = "This can't be appealed.";
    public static final String WINDOW_CLOSED = "The 14 days to appeal have passed.";
    public static final String DECIDED = "This appeal was already decided.";
    public static final String SELF = "Someone other than the person who made the decision must decide the appeal.";
    public static final String NOT_OPEN = "Files can only be added while the appeal is waiting for Northline.";
    public static final String LIFTED = "This consequence was already lifted.";

    private AppealRules() {}

    public static String statement(@Nullable String text) {
        var s = text == null ? "" : text.strip();
        if (s.isEmpty()) {
            throw RuleViolation.of("statement", "required", STATEMENT_REQUIRED);
        }
        if (s.length() > STATEMENT_MAX) {
            throw RuleViolation.of("statement", "length", STATEMENT_TOO_LONG);
        }
        return s;
    }

    public static String reason(@Nullable String text) {
        var s = text == null ? "" : text.strip();
        if (s.isEmpty()) {
            throw RuleViolation.of("reason", "required", REASON_REQUIRED);
        }
        if (s.length() > REASON_MAX) {
            throw RuleViolation.of("reason", "length", REASON_TOO_LONG);
        }
        return s;
    }

    /** The accepted type of an upload (by its declared type), or empty. */
    public static Optional<String> fileType(@Nullable String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        var type = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "image/jpeg", "image/png", "image/heic", "image/heif", "application/pdf" -> Optional.of(type);
            default -> Optional.empty();
        };
    }
}
