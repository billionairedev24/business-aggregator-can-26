package ca.northline.shared;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.regex.Pattern;

/** ULID ids (26 chars, Crockford base32, monotonic within a millisecond). All primary keys are ULID text. */
public final class Ids {
    private static final Pattern ULID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");

    private Ids() {}

    public static String next() {
        return UlidCreator.getMonotonicUlid().toString();
    }

    public static boolean isValid(String id) {
        return ULID.matcher(id).matches();
    }
}
