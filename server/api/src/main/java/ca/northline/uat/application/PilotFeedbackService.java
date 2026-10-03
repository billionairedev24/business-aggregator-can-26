package ca.northline.uat.application;

import static ca.northline.uat.domain.FeedbackRules.BODY_MAX;
import static ca.northline.uat.domain.FeedbackRules.BODY_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.NOT_A_MEMBER;
import static ca.northline.uat.domain.FeedbackRules.NOT_A_PARTICIPANT;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_MAX_BYTES;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_REQUIRED;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_TOO_LARGE;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_TYPE;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_TYPES;
import static ca.northline.uat.domain.FeedbackRules.SCREENSHOT_UNKNOWN;

import ca.northline.shared.Bytes;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.storage.ImageDecoding;
import ca.northline.shared.storage.ObjectKeys;
import ca.northline.uat.api.PilotParticipants;
import ca.northline.uat.application.UatStore.Feedback;
import ca.northline.uat.application.UatStore.Participant;
import ca.northline.uat.application.UatStore.Screenshot;
import ca.northline.uat.domain.FeedbackRules;
import ca.northline.uat.domain.FeedbackState;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PilotFeedback} and {@link PilotParticipants}: who takes part, and what they send. */
@Service
@RequiredArgsConstructor
class PilotFeedbackService implements PilotFeedback, PilotParticipants {

    /** An attached screenshot that was never sent is removed after this long (on the person's next upload). */
    static final Duration UNSENT_SCREENSHOT_TTL = Duration.ofDays(1);

    private final UatStore store;
    private final ScreenshotStorage storage;
    private final MerchantMemberships memberships;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public boolean isParticipant(String userId, @Nullable String merchantId) {
        return participant(userId, merchantId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Status status(String userId, @Nullable String merchantId) {
        var participant = participant(userId, merchantId);
        return new Status(
                participant.isPresent(),
                participant.map(p -> p.persona().code()).orElse(null),
                SCREENSHOT_MAX_BYTES,
                SCREENSHOT_TYPES.stream().sorted().toList());
    }

    @Override
    @Transactional
    public Uploaded screenshot(String userId, String contentType, Bytes bytes) {
        requireParticipant(userId, null, true);
        if (bytes.isEmpty()) {
            throw RuleViolation.of("file", "required", SCREENSHOT_REQUIRED);
        }
        var type = contentType.toLowerCase(Locale.ROOT);
        if (!SCREENSHOT_TYPES.contains(type)) {
            throw RuleViolation.of("file", "format", SCREENSHOT_TYPE);
        }
        if (bytes.size() > SCREENSHOT_MAX_BYTES) {
            throw RuleViolation.of("file", "size", SCREENSHOT_TOO_LARGE);
        }
        // S-104: the bytes must be what the type says, and a readable image within the pixel ceiling (header only)
        if (!signatureMatches(type, bytes.toArray())
                || ImageDecoding.size(bytes.toArray()).isEmpty()) {
            throw RuleViolation.of("file", "format", SCREENSHOT_TYPE);
        }
        var now = clock.instant();
        for (var stale : store.unsentScreenshots(userId, now.minus(UNSENT_SCREENSHOT_TTL))) {
            storage.delete(stale.storageKey());
            store.forgetScreenshot(stale.id());
        }
        var id = Ids.next();
        var shot = new Screenshot(id, userId, ObjectKeys.customerObject(userId, id, type), type, bytes.size(), now);
        storage.put(shot.storageKey(), bytes.toArray(), type);
        store.insert(shot);
        return new Uploaded(id, type, bytes.size());
    }

    @Override
    @Transactional
    public Sent send(Submission s) {
        var participant = requireParticipant(s.userId(), s.merchantId(), false);
        var body = FeedbackRules.text(s.body());
        if (body.isBlank() || body.length() > BODY_MAX) {
            throw RuleViolation.of("body", body.isBlank() ? "required" : "length", BODY_REQUIRED);
        }
        Screenshot shot = null;
        if (s.screenshotId() != null) {
            shot = store.screenshot(s.userId(), s.screenshotId())
                    .orElseThrow(() -> RuleViolation.of("screenshotId", "allowed", SCREENSHOT_UNKNOWN));
            store.forgetScreenshot(shot.id());
        }
        var now = clock.instant();
        var saved = store.insert(new Feedback(
                Ids.next(),
                0,
                participant.id(),
                participant.persona(),
                participant.label(),
                s.userId(),
                participant.merchantId(),
                s.app(),
                s.category(),
                s.severity(),
                body,
                FeedbackRules.route(s.route()),
                s.appVersion().strip(),
                s.locale().strip(),
                FeedbackRules.platform(s.platform()),
                shot == null ? null : shot.storageKey(),
                shot == null ? null : shot.contentType(),
                shot == null ? null : shot.byteSize(),
                FeedbackState.NEW,
                null,
                null,
                null,
                null,
                now,
                now,
                0));
        return new Sent(saved.id(), FeedbackRules.reference(saved.number()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Mine> mine(String userId) {
        return store.feedbackOf(userId).stream()
                .map(f -> new Mine(
                        f.id(),
                        FeedbackRules.reference(f.number()),
                        f.category().code(),
                        f.state().code(),
                        f.route(),
                        f.createdAt()))
                .toList();
    }

    /**
     * The participant row the feedback belongs to: the business's when the person acts for one they're on the team of
     * (Studio), else their own.
     */
    private Optional<Participant> participant(String userId, @Nullable String merchantId) {
        var merchants =
                merchantId != null && memberships.roleOf(merchantId, userId).isPresent()
                        ? List.of(merchantId)
                        : List.<String>of();
        return store.activeFor(userId, merchants).stream()
                .min(Comparator.comparing((Participant p) -> p.merchantId() == null));
    }

    /** @param anyBusiness a screenshot is uploaded before the business is known: any of the person's businesses counts */
    private Participant requireParticipant(String userId, @Nullable String merchantId, boolean anyBusiness) {
        if (merchantId != null && memberships.roleOf(merchantId, userId).isEmpty()) {
            throw new AccessDeniedException(NOT_A_MEMBER);
        }
        var merchants = anyBusiness
                ? memberships.membershipsOf(userId).stream()
                        .map(MerchantMemberships.Membership::merchantId)
                        .toList()
                : merchantId == null ? List.<String>of() : List.of(merchantId);
        return store.activeFor(userId, merchants).stream()
                .min(Comparator.comparing((Participant p) -> p.merchantId() == null))
                .orElseThrow(() -> new AccessDeniedException(NOT_A_PARTICIPANT));
    }

    /** The declared type must match the file's magic bytes (S-104). */
    static boolean signatureMatches(String type, byte[] b) {
        return switch (type) {
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
            case "image/png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            default -> false;
        };
    }
}
