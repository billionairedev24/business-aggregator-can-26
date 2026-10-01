package ca.northline.region.domain;

import java.util.regex.Pattern;

/**
 * Validation messages of the Location screen (S-47). The email rule is validation-rules.md's; the others are ours
 * (the spec has no address section). English in both locales on the server, mirrored in the web app's messages.
 */
public final class GeoMessages {
    private GeoMessages() {}

    public static final String TOO_LONG = "At most 200 characters.";
    public static final String SESSION_FORMAT = "Start the address search again.";
    public static final String NOT_IN_CANADA = "Choose an address in Canada.";
    public static final String REGION_REQUIRED = "Choose where you’d like Northline.";
    public static final String EMAIL_REQUIRED = "Email is required.";
    public static final String EMAIL_FORMAT = "That doesn't look like an email address.";

    /** validation-rules.md § email. */
    public static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    /** Google's session tokens are UUIDs; the fake accepts the same shape. */
    public static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
}
