package ca.northline.account.web;

import ca.northline.account.application.ExportMyData;
import ca.northline.account.application.Preferences.Change;
import ca.northline.account.application.Preferences.ManagePreferences;
import ca.northline.account.application.Preferences.Prefs;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Language &amp; region, Dietary &amp; accessibility and "Download my data" (S-59):
 *
 * <pre>
 * GET   /api/v1/me/preferences   {language, province, units, timeFormat, dietary, allergies, accessibility, accessNotes, display}
 * PATCH /api/v1/me/preferences   any of those (only what is sent changes; codes outside the lists → 422;
 *                                province "" = follow my location again, as "" clears allergies and notes)
 * GET   /api/v1/me/export        the person's account data as a JSON download
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MyPreferencesController {

    private final ManagePreferences preferences;
    private final ExportMyData export;

    record PreferencesRequest(
            @Nullable String language,

            @Schema(
                    description = "Two-letter province or territory code from GET /geo/regions; \"\" goes back to"
                            + " following the person's location. Omitted or null: unchanged.")
            @Nullable
            String province,

            @Nullable String units,
            @Nullable String timeFormat,
            @Nullable List<String> dietary,
            @Nullable String allergies,
            @Nullable List<String> accessibility,
            @Nullable String accessNotes,
            @Nullable List<String> display) {}

    @Operation(summary = "The caller's language, region, dietary and accessibility preferences")
    @GetMapping("/preferences")
    ResponseEntity<Prefs> get(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.view(user.userId()));
    }

    @Operation(summary = "Change the caller's preferences")
    @PatchMapping("/preferences")
    Prefs patch(CurrentUser user, @RequestBody PreferencesRequest b) {
        return preferences.update(
                user.userId(),
                new Change(
                        b.language(),
                        b.province(),
                        b.units(),
                        b.timeFormat(),
                        b.dietary(),
                        b.allergies(),
                        b.accessibility(),
                        b.accessNotes(),
                        b.display()));
    }

    @Operation(summary = "Download the caller's account data (JSON)")
    @GetMapping("/export")
    ResponseEntity<ExportMyData.Export> export(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("northline-my-data.json")
                                .build()
                                .toString())
                .body(export.of(user.userId()));
    }
}
