package ca.northline.privacy.application;

import ca.northline.payments.api.PaymentStepUp;
import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.application.PrivacyRequests.Link;
import ca.northline.privacy.application.PrivacyRequests.RequestView;
import ca.northline.privacy.application.PrivacyRequests.SelfService;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.Verification;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SelfService}: people's own requests from their account, for consumers and team members alike. Identity is
 * verified with a fresh step-up proof (passkey or authenticator, northline-auth) or a code texted to the account's
 * verified mobile — a phone-code account has no other factor, and the app must be able to delete it (App Store
 * 5.1.1(v), Google Play).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class SelfServiceImpl implements SelfService {

    static final String PUBLIC_PATH = "/api/v1/public/privacy-exports/";
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Duration RESEND_AFTER = Duration.ofMinutes(1);
    static final int TEXTS_PER_HOUR = 3;
    static final int TEXTS_PER_DAY = 6;

    private final PrivacyRequestStore store;
    private final Intake intake;
    private final Views views;
    private final Contributors contributors;
    private final PaymentStepUp stepUp;
    private final CodeSender codes;
    private final PrivacySettings settings;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<RequestView> mine(String userId, Locale locale) {
        return store.of(userId).stream().map(r -> views.view(r, locale, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RequestView one(String userId, String requestId, Locale locale) {
        return views.view(own(userId, requestId), locale, true);
    }

    @Override
    public RequestView open(Open command, Locale locale) {
        var holder = intake.holder(command.userId());
        var verification = command.stepUpProof() == null ? null : proven(command.userId(), command.stepUpProof());
        var now = clock.instant();
        var request =
                intake.open(holder, command.type(), command.corrections(), command.note(), verification, null, now);
        // S-104: over the person's texting budget the request still opens; it waits for a step-up proof or a later code
        if (verification == null && holder.phone() != null && withinTextBudget(command.userId(), now)) {
            request = text(request, holder.phone(), locale);
        }
        return views.view(request, locale, true);
    }

    @Override
    public RequestView sendCode(String userId, String requestId, Locale locale) {
        var request = awaiting(own(userId, requestId));
        var holder = intake.holder(userId);
        if (holder.phone() == null) {
            throw new Conflict("no_mobile", PrivacyRules.NO_MOBILE);
        }
        var now = clock.instant();
        var expires = request.codeExpiresAt();
        if (expires != null
                && expires.minus(settings.codeLife()).plus(RESEND_AFTER).isAfter(now)) {
            throw new Conflict("code_too_soon", PrivacyRules.CODE_TOO_SOON);
        }
        if (!withinTextBudget(userId, now)) {
            throw new Conflict("too_many_codes", PrivacyRules.TOO_MANY_CODES);
        }
        return views.view(text(request, holder.phone(), locale), locale, true);
    }

    /**
     * S-104: codes texted to one person, across all their requests — {@value #TEXTS_PER_HOUR} an hour,
     * {@value #TEXTS_PER_DAY} a day. Without it, opening, withdrawing and reopening a request texted without limit (SMS
     * cost) and every new code allowed {@link PrivacyRules#MAX_CODE_ATTEMPTS} more guesses.
     */
    private boolean withinTextBudget(String userId, Instant now) {
        return store.textsSince(userId, now.minus(Duration.ofHours(1))) < TEXTS_PER_HOUR
                && store.textsSince(userId, now.minus(Duration.ofDays(1))) < TEXTS_PER_DAY;
    }

    private Request text(Request request, String phone, Locale locale) {
        var issued = intake.newCode(request, clock.instant());
        var saved = intake.save(issued.request());
        try {
            codes.send(phone, issued.code(), locale);
        } catch (RuntimeException e) {
            // the request (or the new code) rolls back: the person tries again
            log.warn(
                    "Privacy request {}: verification code not sent ({})",
                    saved.id(),
                    e.getClass().getSimpleName());
            throw new Conflict("code_not_sent", PrivacyRules.CODE_NOT_SENT);
        }
        store.textSent(saved.subjectId(), saved.id(), clock.instant());
        intake.record(saved, saved.subjectId(), "self", "code_sent", Map.of("channel", "sms"));
        return saved;
    }

    @Override
    @Transactional(noRollbackFor = RuleViolation.class)
    public RequestView verify(
            String userId, String requestId, @Nullable String code, @Nullable String proof, Locale locale) {
        var request = awaiting(own(userId, requestId));
        var now = clock.instant();
        Verification how;
        if (code != null) {
            var trimmed = code.strip();
            if (!CODE.matcher(trimmed).matches()) {
                throw RuleViolation.of("code", "format", PrivacyRules.CODE_FORMAT);
            }
            if (request.codeAttempts() >= PrivacyRules.MAX_CODE_ATTEMPTS) {
                throw new Conflict("code_locked", PrivacyRules.CODE_LOCKED);
            }
            if (!intake.codeMatches(request, trimmed, now)) {
                intake.save(request.withCodeAttempts(request.codeAttempts() + 1));
                throw RuleViolation.of("code", "match", PrivacyRules.CODE_WRONG);
            }
            how = Verification.CODE;
        } else {
            how = proven(userId, proof);
        }
        var verified = request.withState(RequestState.VERIFIED)
                .withVerification(how)
                .withVerifiedAt(now)
                .withCodeHash(null)
                .withCodeExpiresAt(null);
        verified = intake.save(verified.withScheduledFor(intake.scheduleFor(verified, now)));
        intake.verified(verified, now);
        intake.record(verified, userId, "self", "verified", Map.of("verification", how.code()));
        return views.view(verified, locale, true);
    }

    @Override
    public RequestView withdraw(String userId, String requestId, Locale locale) {
        var request = own(userId, requestId);
        if (request.state() == RequestState.IN_PROGRESS) {
            throw new Conflict("not_withdrawable", PrivacyRules.NOT_WITHDRAWABLE);
        }
        if (!request.state().canMoveTo(RequestState.WITHDRAWN)) {
            throw new Conflict("closed", PrivacyRules.CLOSED);
        }
        var withdrawn = intake.save(intake.closedUnfinished(request.withState(RequestState.WITHDRAWN)));
        intake.record(
                withdrawn,
                userId,
                "self",
                "withdrawn",
                Map.of("type", withdrawn.type().code()));
        return views.view(withdrawn, locale, true);
    }

    @Override
    public Link link(String userId, String requestId) {
        var request = own(userId, requestId);
        var now = clock.instant();
        if (request.type() != RequestType.ACCESS || request.state() != RequestState.COMPLETED) {
            throw new Conflict("export_not_ready", PrivacyRules.EXPORT_NOT_READY);
        }
        var expires = request.exportExpiresAt();
        if (request.exportKey() == null || expires == null || !expires.isAfter(now)) {
            throw new Conflict("export_gone", PrivacyRules.EXPORT_GONE);
        }
        var token = Intake.token();
        var linkExpires = now.plus(settings.linkLife());
        if (linkExpires.isAfter(expires)) {
            linkExpires = expires;
        }
        var saved = intake.save(request.withLinkHash(Intake.sha256(token)).withLinkExpiresAt(linkExpires));
        intake.record(saved, userId, "self", "link_issued", Map.of("expiresAt", linkExpires.toString()));
        return new Link(PUBLIC_PATH + token, PUBLIC_PATH + token + "?part=summary", linkExpires);
    }

    @Override
    public Set<String> correctable() {
        return contributors.correctable();
    }

    /** The caller's own request (someone else's is a 404, never a 403). */
    private Request own(String userId, String requestId) {
        return store.find(requestId)
                .filter(r -> r.subjectId().equals(userId))
                .orElseThrow(() -> new NotFound("privacy_request", requestId));
    }

    private static Request awaiting(Request request) {
        if (request.state() != RequestState.AWAITING_VERIFICATION) {
            throw new Conflict("not_awaiting", PrivacyRules.NOT_AWAITING);
        }
        return request;
    }

    private Verification proven(String userId, @Nullable String proof) {
        if (!stepUp.verified(userId, proof)) {
            throw new IdentityCheckRequired();
        }
        return Verification.STEP_UP;
    }
}
