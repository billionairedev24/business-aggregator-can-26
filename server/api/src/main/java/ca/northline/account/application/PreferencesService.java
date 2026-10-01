package ca.northline.account.application;

import static ca.northline.account.domain.PreferenceRules.ACCESSIBILITY;
import static ca.northline.account.domain.PreferenceRules.DIETARY;
import static ca.northline.account.domain.PreferenceRules.DISPLAY;

import ca.northline.account.application.Preferences.Change;
import ca.northline.account.application.Preferences.ManagePreferences;
import ca.northline.account.application.Preferences.PreferencesStore;
import ca.northline.account.application.Preferences.Prefs;
import ca.northline.account.application.Preferences.Stored;
import ca.northline.account.domain.PreferenceRules;
import ca.northline.identity.api.AccountFacts;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Language &amp; region and Dietary &amp; accessibility (S-59). Stored for now: filtering shops by diet, sharing the
 * notes with visiting providers and applying the display choices are later work (DECISIONS S-59).
 */
@Service
@RequiredArgsConstructor
@Transactional
class PreferencesService implements ManagePreferences {

    private final PreferencesStore store;
    private final AccountFacts identity;

    @Override
    @Transactional(readOnly = true)
    public Prefs view(String userId) {
        var s = store.find(userId).orElse(Stored.DEFAULTS);
        var locale = identity.profile(userId).locale();
        return new Prefs(
                locale != null && locale.startsWith("fr") ? "fr" : "en",
                s.province(),
                s.units(),
                s.timeFormat(),
                s.dietary(),
                s.allergies(),
                s.accessibility(),
                s.accessNotes(),
                s.display());
    }

    @Override
    public Prefs update(String userId, Change c) {
        var language = PreferenceRules.code(c.language(), PreferenceRules.LANGUAGES, "language");
        var province = PreferenceRules.province(c.province());
        var units = PreferenceRules.code(c.units(), PreferenceRules.UNITS, "units");
        var time = PreferenceRules.code(c.timeFormat(), PreferenceRules.TIME_FORMATS, "timeFormat");
        var dietary = PreferenceRules.codes(c.dietary(), DIETARY, "dietary");
        var access = PreferenceRules.codes(c.accessibility(), ACCESSIBILITY, "accessibility");
        var display = PreferenceRules.codes(c.display(), DISPLAY, "display");
        var allergies = PreferenceRules.text(
                c.allergies(), PreferenceRules.ALLERGIES_MAX, "allergies", PreferenceRules.ALLERGIES);
        var notes =
                PreferenceRules.text(c.accessNotes(), PreferenceRules.NOTES_MAX, "accessNotes", PreferenceRules.NOTES);
        var s = store.find(userId).orElse(Stored.DEFAULTS);
        store.save(
                userId,
                new Stored(
                        province != null ? province : s.province(),
                        Objects.requireNonNullElse(units, s.units()),
                        Objects.requireNonNullElse(time, s.timeFormat()),
                        Objects.requireNonNullElse(dietary, s.dietary()),
                        allergies != null ? emptyToNull(allergies) : s.allergies(),
                        Objects.requireNonNullElse(access, s.accessibility()),
                        notes != null ? emptyToNull(notes) : s.accessNotes(),
                        Objects.requireNonNullElse(display, s.display())));
        if (language != null) {
            identity.locale(userId, "fr".equals(language) ? "fr-CA" : "en-CA");
        }
        return view(userId);
    }

    private static @Nullable String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
