package ca.northline.shared.storage;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Object key rules. Uploads are stored per module and per merchant: {@code <module>/<merchantId>/<ulid>[.<ext>]} —
 * the module prefix comes from {@link ObjectStore#within(String)}, the rest from {@link #merchantObject}. One prefix per
 * merchant lets retention and erasure (S-105) work on {@code <module>/<merchantId>/}.
 */
public final class ObjectKeys {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");
    private static final int MAX_LENGTH = 512;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic",
            "image/heif", "heif",
            "image/svg+xml", "svg",
            "application/pdf", "pdf");

    private ObjectKeys() {}

    /** {@code <merchantId>/<objectId>[.<ext>]}, the extension taken from the content type when it is a known one. */
    public static String merchantObject(String merchantId, String objectId, String contentType) {
        var ext = EXTENSIONS.get(contentType.toLowerCase(Locale.ROOT));
        return requireValid(merchantId + "/" + objectId + (ext == null ? "" : "." + ext));
    }

    /**
     * @return the key, when it is a relative path of {@code [A-Za-z0-9._-]} segments that neither start with a dot nor
     *     climb out ({@code ..}); otherwise {@link IllegalArgumentException}
     */
    public static String requireValid(String key) {
        if (key.length() > MAX_LENGTH || !KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Invalid object key: " + key);
        }
        return key;
    }
}
