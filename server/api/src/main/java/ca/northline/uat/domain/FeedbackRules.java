package ca.northline.uat.domain;

import ca.northline.platform.Redaction;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a participant's feedback may hold and how it is cleaned before it is stored: the text and the device line go
 * through the log redaction (S-112: secrets, emails, phone numbers, card numbers, codes, postal codes), and the route
 * loses its scheme, host, query string and fragment, and any path segment that is or follows a token.
 */
public final class FeedbackRules {

    public static final int BODY_MAX = 4000;
    public static final int ROUTE_MAX = 500;
    public static final int PLATFORM_MAX = 200;
    public static final int NOTE_MAX = 1000;
    public static final int COMMENTS_MAX = 2000;
    public static final int LABEL_MAX = 80;
    public static final int TRACKER_MAX = 500;
    /** Screenshots: PNG or JPEG, at most 5 MB (S-104's upload checks: declared type, magic bytes, header size). */
    public static final long SCREENSHOT_MAX_BYTES = 5L * 1024 * 1024;

    public static final Set<String> SCREENSHOT_TYPES = Set.of("image/png", "image/jpeg");

    public static final String CATEGORY_REQUIRED = "Choose bug, confusing, idea or praise.";
    public static final String SEVERITY_REQUIRED = "Choose how much it got in your way.";
    public static final String BODY_REQUIRED = "Tell us what happened, in 1 to 4,000 characters.";
    public static final String APP_REQUIRED = "Choose the app the feedback is about.";
    public static final String CONTEXT_REQUIRED = "The app didn't send its screen, version, language or device.";
    public static final String SCREENSHOT_REQUIRED = "Choose a screenshot to attach.";
    public static final String SCREENSHOT_TYPE = "Attach the screenshot as a PNG or JPEG image.";
    public static final String SCREENSHOT_TOO_LARGE = "The screenshot must be 5 MB or smaller.";
    public static final String SCREENSHOT_UNKNOWN = "That screenshot isn't yours or has expired; attach it again.";
    public static final String NOT_A_PARTICIPANT = "Feedback here is for pilot participants.";
    public static final String NOT_A_MEMBER = "You're not on this business's team.";
    public static final String STATE_REQUIRED = "Choose the new state.";
    public static final String MOVE_NOT_ALLOWED = "This item can't move to that state from where it is.";
    public static final String BLOCKING_REQUIRED = "Say whether it blocks the launch.";
    public static final String DUPLICATE_REQUIRED = "Choose the item this one duplicates.";
    public static final String DUPLICATE_SELF = "An item can't duplicate itself or one of its own duplicates.";
    public static final String NOTE_TOO_LONG = "Keep the note under 1,000 characters.";
    public static final String OWNER_NOT_STAFF = "Choose someone on the Northline team who works on UAT.";
    public static final String TRACKER_FORMAT =
            "Enter the tracker issue's web address (https://…), up to 500 characters.";
    public static final String PERSONA_REQUIRED = "Choose provider, seller, kitchen, customer, courier or staff.";
    public static final String LABEL_REQUIRED = "Enter a working name, 1 to 80 characters.";
    public static final String WHO_REQUIRED = "Enter the person's email or mobile number, or the business's id.";
    public static final String WHO_UNKNOWN = "No Northline account or business matches that.";
    public static final String ALREADY_PARTICIPANT = "They already take part with this persona.";
    public static final String SCRIPT_REQUIRED = "Choose the UAT script.";
    public static final String SCRIPT_PERSONA = "That script is for another persona.";
    public static final String OUTCOME_REQUIRED = "Choose signed off, signed off with comments, or blocked.";
    public static final String BLOCKED_NEEDS_ITEMS = "Name the blocking items or describe them in the comments.";
    public static final String COMMENTS_TOO_LONG = "Keep the comments under 2,000 characters.";
    public static final String BLOCKING_UNKNOWN = "Pick blocking items from the UAT feedback queue.";
    public static final String PERSONA_BUSINESS = "Pick the persona that matches the business's type.";
    public static final String STALE = "Someone else changed this item; reload it.";
    public static final String PARTICIPANT_INACTIVE = "This participant no longer takes part.";

    private static final Pattern SCHEME_HOST = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*");
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern TOKENISH = Pattern.compile("[A-Za-z0-9_~.=-]{24,}");
    private static final Pattern DIGITS = Pattern.compile("\\d");
    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]");
    /** Path segments whose next segment is a secret (team invitations, pilot invites, unsubscribe and email links). */
    private static final Set<String> TOKEN_PARENTS = Set.of(
            "invite",
            "invites",
            "invitation",
            "invitations",
            "team-invitations",
            "pilot-invites",
            "unsubscribe",
            "verify",
            "reset",
            "token",
            "tokens",
            "magic",
            "link",
            "links",
            "download",
            "downloads");

    public static final String TOKEN = ":token";

    private FeedbackRules() {}

    /**
     * The route as stored: path only (no scheme, host, query or fragment), token segments replaced by
     * {@value #TOKEN}, then the log redaction; {@code /} when nothing is left, cut to {@value #ROUTE_MAX} characters.
     */
    public static String route(String raw) {
        var path = SCHEME_HOST.matcher(raw.strip()).replaceFirst("");
        var cut = Stream.of(path.indexOf('?'), path.indexOf('#'))
                .filter(i -> i >= 0)
                .min(Integer::compare)
                .orElse(path.length());
        path = path.substring(0, cut);
        var segments = path.split("/", -1);
        for (var i = 0; i < segments.length; i++) {
            var previous = i > 0 ? segments[i - 1].toLowerCase(Locale.ROOT) : "";
            if (!segments[i].isEmpty() && (TOKEN_PARENTS.contains(previous) || looksLikeToken(segments[i]))) {
                segments[i] = TOKEN;
            }
        }
        var clean = Redaction.redact(String.join("/", segments));
        if (clean.isBlank()) {
            return "/";
        }
        return clean.length() > ROUTE_MAX ? clean.substring(0, ROUTE_MAX) : clean;
    }

    /** Long random-looking strings (letters and digits mixed, 24+ characters) that are not ULIDs. */
    static boolean looksLikeToken(String segment) {
        return TOKENISH.matcher(segment).matches()
                && !ULID.matcher(segment).matches()
                && DIGITS.matcher(segment).find()
                && LETTERS.matcher(segment).find()
                && !segment.contains(".");
    }

    /** Free text as stored: trimmed, then the log redaction. */
    public static String text(String raw) {
        return Redaction.redact(raw.strip());
    }

    /** The browser / OS line: redacted, control characters dropped, cut to {@value #PLATFORM_MAX} characters. */
    public static String platform(String raw) {
        var clean = text(raw)
                .codePoints()
                .filter(c -> !Character.isISOControl(c))
                .mapToObj(Character::toString)
                .collect(Collectors.joining());
        return clean.length() > PLATFORM_MAX ? clean.substring(0, PLATFORM_MAX) : clean;
    }

    /** {@code UAT-1001}. */
    public static String reference(long number) {
        return "UAT-" + number;
    }
}
