package ca.northline.account.application;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Language &amp; region and Dietary &amp; accessibility (design 06 account, S-59): inbound port and its store
 * ({@code account.preferences}). The app language itself is the person's {@code identity.users.locale}.
 */
public final class Preferences {
    private Preferences() {}

    /**
     * @param language {@code en | fr}
     * @param province the province the person shops in (two letters), or null to follow their location
     * @param units {@code metric | imperial}
     * @param timeFormat {@code 12h | 24h}
     */
    public record Prefs(
            String language,
            @Nullable String province,
            String units,
            String timeFormat,
            List<String> dietary,
            @Nullable String allergies,
            List<String> accessibility,
            @Nullable String accessNotes,
            List<String> display) {

        public Prefs {
            dietary = List.copyOf(dietary);
            accessibility = List.copyOf(accessibility);
            display = List.copyOf(display);
        }
    }

    /** Only what is sent changes; {@code ""} clears {@code province} (follow my location), allergies and notes. */
    public record Change(
            @Nullable String language,
            @Nullable String province,
            @Nullable String units,
            @Nullable String timeFormat,
            @Nullable List<String> dietary,
            @Nullable String allergies,
            @Nullable List<String> accessibility,
            @Nullable String accessNotes,
            @Nullable List<String> display) {}

    public interface ManagePreferences {
        Prefs view(String userId);

        Prefs update(String userId, Change change);
    }

    /** Outbound port: the stored part (everything but the language). */
    public interface PreferencesStore {
        Optional<Stored> find(String userId);

        void save(String userId, Stored stored);
    }

    public record Stored(
            @Nullable String province,
            String units,
            String timeFormat,
            List<String> dietary,
            @Nullable String allergies,
            List<String> accessibility,
            @Nullable String accessNotes,
            List<String> display) {

        public static final Stored DEFAULTS =
                new Stored(null, "metric", "12h", List.of(), null, List.of(), null, List.of());

        public Stored {
            dietary = List.copyOf(dietary);
            accessibility = List.copyOf(accessibility);
            display = List.copyOf(display);
        }
    }
}
