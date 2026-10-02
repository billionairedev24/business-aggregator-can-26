package ca.northline.privacy.application;

import ca.northline.identity.api.PrivacyAccounts;
import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.application.PrivacyRequestStore.Step;
import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.PrivacyRequests.Desk;
import ca.northline.privacy.application.PrivacyRequests.DeskItem;
import ca.northline.privacy.application.PrivacyRequests.RequestView;
import ca.northline.privacy.domain.Decision;
import ca.northline.privacy.domain.ExtensionReason;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.privacy.domain.PrivacyRules.Correction;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.Verification;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Desk}: the console's privacy queue. Staff record requests that arrived by email or mail, verify the person,
 * extend a deadline once (when the law allows), refuse with a reason, start an erasure early, apply corrections and
 * retry failed erasure steps. Every action is in the audit log with the staff member's roles.
 */
@Service
@RequiredArgsConstructor
@Transactional
class DeskService implements Desk {

    private static final int QUEUE_LIMIT = 200;

    private final PrivacyRequestStore store;
    private final Intake intake;
    private final Views views;
    private final Contributors contributors;
    private final PrivacyAccounts accounts;
    private final PrivacyRegimes regimes;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<DeskItem> queue(boolean open, @Nullable RequestType type, Locale locale) {
        var states = open ? RequestState.OPEN : EnumSet.complementOf(EnumSet.copyOf(RequestState.OPEN));
        return views.items(store.queue(states, type, QUEUE_LIMIT), locale);
    }

    @Override
    @Transactional(readOnly = true)
    public RequestView detail(String requestId, Locale locale) {
        return views.view(find(requestId), locale, true);
    }

    @Override
    public RequestView record(Recorded command, Locale locale) {
        var userId = accounts.find(command.contact())
                .orElseThrow(() -> RuleViolation.of("contact", "exists", PrivacyRules.NO_ACCOUNT));
        var request = intake.open(
                intake.holder(userId),
                command.type(),
                command.corrections(),
                command.note(),
                null,
                command.actor().userId(),
                clock.instant());
        return views.view(request, locale, true);
    }

    @Override
    public RequestView verify(String requestId, Actor actor, Locale locale) {
        var request = lock(requestId);
        if (request.state() != RequestState.AWAITING_VERIFICATION) {
            throw new Conflict("not_awaiting", PrivacyRules.NOT_AWAITING);
        }
        var now = clock.instant();
        var verified = request.withState(RequestState.VERIFIED)
                .withVerification(Verification.STAFF)
                .withVerifiedAt(now)
                .withVerifiedBy(actor.userId())
                .withCodeHash(null)
                .withCodeExpiresAt(null);
        verified = intake.save(verified.withScheduledFor(intake.scheduleFor(verified, now)));
        intake.verified(verified, now);
        intake.record(verified, actor.userId(), actor.roles(), "verified", Map.of("verification", "staff"));
        return views.view(verified, locale, true);
    }

    @Override
    public RequestView extend(String requestId, ExtensionReason reason, Actor actor, Locale locale) {
        var request = lock(requestId);
        if (!request.state().open()) {
            throw new Conflict("closed", PrivacyRules.CLOSED);
        }
        if (request.extendedTo() != null) {
            throw new Conflict("already_extended", PrivacyRules.ALREADY_EXTENDED);
        }
        var regime = regimes.of(request.law(), request.province());
        if (!regime.extendable()) {
            throw new Conflict("no_extension", PrivacyRules.NO_EXTENSION);
        }
        var to = regimes.deadline(regime, request.dueAt(), regime.extensionDays());
        var extended = intake.save(request.withExtendedTo(to).withExtensionReason(reason));
        intake.record(
                extended,
                actor.userId(),
                actor.roles(),
                "extended",
                Map.of("reason", reason.code(), "extendedTo", to.toString()));
        return views.view(extended, locale, true);
    }

    @Override
    public RequestView reject(String requestId, Decision decision, @Nullable String note, Actor actor, Locale locale) {
        var request = lock(requestId);
        if (!request.state().canMoveTo(RequestState.REJECTED)) {
            throw new Conflict(
                    "closed",
                    request.state() == RequestState.IN_PROGRESS ? PrivacyRules.NOT_WITHDRAWABLE : PrivacyRules.CLOSED);
        }
        var rejected = intake.save(intake.closedUnfinished(request.withState(RequestState.REJECTED)
                .withDecision(decision)
                .withDecisionNote(note == null || note.isBlank() ? null : note.strip())
                .withDecidedBy(actor.userId())
                .withCompletedAt(clock.instant())));
        intake.record(rejected, actor.userId(), actor.roles(), "rejected", Map.of("decision", decision.code()));
        return views.view(rejected, locale, true);
    }

    @Override
    public RequestView start(String requestId, Actor actor, Locale locale) {
        var request = lock(requestId);
        if (request.type() != RequestType.ERASURE || request.state() != RequestState.VERIFIED) {
            throw new Conflict("not_startable", PrivacyRules.NOT_STARTABLE);
        }
        var started = intake.save(request.withScheduledFor(clock.instant()));
        intake.record(started, actor.userId(), actor.roles(), "start_requested", Map.of("type", "erasure"));
        return views.view(started, locale, true);
    }

    @Override
    public RequestView correct(String requestId, List<Correction> accepted, Actor actor, Locale locale) {
        var request = lock(requestId);
        if (request.type() != RequestType.CORRECTION || request.state() != RequestState.VERIFIED) {
            throw new Conflict("not_correction", PrivacyRules.NOT_CORRECTION);
        }
        var corrections = PrivacyRules.corrections(accepted, contributors.correctable());
        var holder = intake.holder(request.subjectId());
        var subject = new Subject(holder.id(), holder.email(), holder.phone(), Locale.forLanguageTag(holder.locale()));
        for (var i = 0; i < corrections.size(); i++) {
            var correction = corrections.get(i);
            try {
                contributors.forField(correction.field()).correct(subject, correction.field(), correction.value());
            } catch (RuleViolation e) {
                var v = e.getViolations().getFirst();
                throw RuleViolation.of("corrections[%d].value".formatted(i), v.rule(), v.message());
            }
            intake.record(
                    request, actor.userId(), actor.roles(), "correction_applied", Map.of("field", correction.field()));
        }
        var now = clock.instant();
        var done = intake.save(request.withState(RequestState.COMPLETED)
                .withStartedAt(now)
                .withCompletedAt(now)
                .withDecidedBy(actor.userId())
                .withSealed(null));
        intake.record(done, actor.userId(), actor.roles(), "completed", Map.of("corrections", corrections.size()));
        return views.view(done, locale, true);
    }

    @Override
    public RequestView retry(String requestId, Actor actor, Locale locale) {
        var request = find(requestId);
        var now = clock.instant();
        var retried = 0;
        for (var step : store.steps(requestId)) {
            if (Step.FAILED.equals(step.status()) || Step.HELD.equals(step.status())) {
                store.saveStep(step.withNextAttemptAt(now));
                retried++;
            }
        }
        intake.record(request, actor.userId(), actor.roles(), "retried", Map.of("steps", retried));
        return views.view(request, locale, true);
    }

    private Request find(String requestId) {
        return store.find(requestId).orElseThrow(() -> new NotFound("privacy_request", requestId));
    }

    private Request lock(String requestId) {
        return store.lock(requestId).orElseThrow(() -> new NotFound("privacy_request", requestId));
    }
}
