package ca.northline.privacy.application;

import ca.northline.privacy.application.PrivacyRequests.Download;
import ca.northline.privacy.application.PrivacyRequests.Downloads;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Downloads}: the export behind a download link. The link's token is the authorization (no session: the
 * app's share sheet or the browser fetches it), so it is long, random, stored hashed, and lives
 * {@link PrivacySettings#linkLife()} at most; an unknown or expired token is a 404, whatever the reason.
 */
@Service
@RequiredArgsConstructor
class DownloadService implements Downloads {

    private final PrivacyRequestStore store;
    private final ExportStorage storage;
    private final ExportBundles bundles;
    private final Intake intake;
    private final Clock clock;

    @Override
    @Transactional
    public Download download(String token, String part) {
        var now = clock.instant();
        var request = store.byLink(Intake.sha256(token))
                .filter(r -> r.linkExpiresAt() != null && r.linkExpiresAt().isAfter(now))
                .orElseThrow(() -> new NotFound("privacy_export", "link"));
        var key = request.exportKey();
        if (key == null) {
            throw new Conflict("export_gone", PrivacyRules.EXPORT_GONE);
        }
        var stored = storage.get(key).orElseThrow(() -> new Conflict("export_gone", PrivacyRules.EXPORT_GONE));
        var opened = bundles.open(request, stored);
        var reference = PrivacyRules.reference(request.number());
        intake.record(request, request.subjectId(), "self", "export_downloaded", Map.of("part", part));
        return "summary".equals(part)
                ? new Download(
                        "northline-" + reference + "-summary.txt",
                        "text/plain;charset=UTF-8",
                        Bytes.of(opened.summary().getBytes(StandardCharsets.UTF_8)))
                : new Download(
                        "northline-" + reference + ".json",
                        "application/json",
                        Bytes.of(opened.data().getBytes(StandardCharsets.UTF_8)));
    }
}
