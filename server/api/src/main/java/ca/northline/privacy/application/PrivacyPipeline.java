package ca.northline.privacy.application;

import ca.northline.identity.api.PrivacyAccounts;
import ca.northline.privacy.api.MerchantDataErased;
import ca.northline.privacy.api.PersonalDataErased;
import ca.northline.privacy.application.PrivacyRequestStore.Kept;
import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.application.PrivacyRequestStore.Step;
import ca.northline.privacy.application.PrivacyRequestStore.StepKey;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link PrivacyWork}: the access exports and the erasure pipeline.
 *
 * <p><b>Erasure</b> — when a verified erasure's grace period ends: the account is closed at once (status {@code
 * erased}, sign-ins ended, so no token is issued for it again), one step per module is written, and each module's
 * {@link PersonalDataContributor#erase} runs <em>in one transaction with its step's row</em>. A crash, a deploy or a
 * failure leaves the step {@code pending} or {@code failed} and the next run takes it up again (backing off); every
 * module's erasure is idempotent, so running one twice is harmless. A step that reports a hold (an open order, an
 * open dispute) is {@code held} and retried every {@link PrivacySettings#holdBackoff()}. The request completes once no
 * step is pending or failed; holds keep being retried after that, and the sealed contact is wiped when the last one
 * clears. Replicas share the work: steps are claimed {@code FOR UPDATE SKIP LOCKED}.
 */
@Slf4j
@Service
class PrivacyPipeline implements PrivacyWork {

    private static final int BATCH = 20;
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final PrivacyRequestStore store;
    private final Contributors contributors;
    private final Intake intake;
    private final Secrets secrets;
    private final ExportBundles bundles;
    private final ExportStorage storage;
    private final PrivacyAccounts accounts;
    private final PrivacySettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    PrivacyPipeline(
            PrivacyRequestStore store,
            Contributors contributors,
            Intake intake,
            Secrets secrets,
            ExportBundles bundles,
            ExportStorage storage,
            PrivacyAccounts accounts,
            PrivacySettings settings,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.contributors = contributors;
        this.intake = intake;
        this.secrets = secrets;
        this.bundles = bundles;
        this.storage = storage;
        this.accounts = accounts;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int runDue() {
        var done = 0;
        for (var id : store.due(clock.instant(), BATCH)) {
            try {
                done += run(id) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn(
                        "Privacy request {}: run failed ({}); retried on the next run",
                        id,
                        e.getClass().getName());
            }
        }
        for (var id : store.withDueSteps(clock.instant(), BATCH)) {
            try {
                steps(id);
                finish(id);
            } catch (RuntimeException e) {
                log.warn(
                        "Privacy request {}: steps failed ({})",
                        id,
                        e.getClass().getName());
            }
        }
        return done;
    }

    @Override
    public boolean run(String requestId) {
        var request = store.find(requestId).orElseThrow();
        return switch (request.type()) {
            case ACCESS -> export(requestId);
            case ERASURE -> {
                begin(requestId);
                steps(requestId);
                yield finish(requestId);
            }
            case CORRECTION -> false;
        };
    }

    /** Builds, seals and stores the access export; completes the request. */
    private boolean export(String requestId) {
        return Boolean.TRUE.equals(tx.execute(_ -> {
            var request = store.lock(requestId).orElseThrow();
            var now = clock.instant();
            if (!due(request, now)) {
                return false;
            }
            var subject = subject(request);
            var sections = contributors.ordered().stream()
                    .flatMap(c -> c.export(subject).stream())
                    .toList();
            var bytes = bundles.build(request, sections, subject.locale(), now);
            var key = "exports/" + request.id() + ".bin";
            storage.put(key, bytes);
            var done = intake.save(request.withState(RequestState.COMPLETED)
                    .withStartedAt(request.startedAt() == null ? now : request.startedAt())
                    .withCompletedAt(now)
                    .withExportKey(key)
                    .withExportBytes((long) bytes.length)
                    .withExportExpiresAt(now.plus(settings.exportLife()))
                    .withSealed(null));
            intake.record(
                    done,
                    Intake.SYSTEM,
                    Intake.SYSTEM,
                    "export_ready",
                    Map.of("sections", sections.size(), "bytes", bytes.length));
            return true;
        }));
    }

    /** Starts the erasure: closes the account and writes the steps (both idempotent). */
    private void begin(String requestId) {
        tx.executeWithoutResult(_ -> {
            var request = store.lock(requestId).orElseThrow();
            var now = clock.instant();
            if (request.state() == RequestState.VERIFIED && due(request, now)) {
                request =
                        intake.save(request.withState(RequestState.IN_PROGRESS).withStartedAt(now));
                intake.record(
                        request,
                        Intake.SYSTEM,
                        Intake.SYSTEM,
                        "erasure_started",
                        Map.of("steps", contributors.ordered().size()));
            }
            if (request.state() == RequestState.IN_PROGRESS) {
                accounts.close(request.subjectId(), now);
                store.createSteps(
                        request.id(),
                        contributors.ordered().stream()
                                .map(c -> new StepKey(c.module(), c.order()))
                                .toList(),
                        now);
            }
        });
    }

    /** Runs every due step, each in its own transaction with its row. */
    private void steps(String requestId) {
        var request = store.find(requestId).orElseThrow();
        if (request.state() != RequestState.IN_PROGRESS && request.state() != RequestState.COMPLETED) {
            return;
        }
        var subject = subject(request);
        for (var step : store.steps(requestId)) {
            if (!stepDue(step, clock.instant())) {
                continue;
            }
            try {
                tx.executeWithoutResult(_ -> erase(request, step.module(), subject));
            } catch (RuntimeException e) {
                log.warn(
                        "Privacy request {}: erasure in {} failed ({}); retried later",
                        requestId,
                        step.module(),
                        e.getClass().getName());
                tx.executeWithoutResult(_ -> failed(requestId, step.module(), e));
            }
        }
    }

    private void erase(Request request, String module, Subject subject) {
        var step = store.lockStep(request.id(), module).orElse(null);
        var now = clock.instant();
        if (step == null || !stepDue(step, now)) {
            return; // another replica has it, or it was done meanwhile
        }
        var contributor = contributors
                .module(module)
                .orElseThrow(() -> new IllegalStateException("No privacy contributor for " + module));
        var outcome = contributor.erase(subject);
        var held = !outcome.held().isEmpty();
        store.saveStep(step.withStatus(held ? Step.HELD : Step.DONE)
                .withAttempts(step.attempts() + 1)
                .withLastError(null)
                .withHolds(outcome.held().stream()
                        .map(k -> new Kept(k.category(), k.reason().code()))
                        .toList())
                .withRetained(outcome.retained().stream()
                        .map(k -> new Kept(k.category(), k.reason().code()))
                        .toList())
                .withNextAttemptAt(held ? now.plus(settings.holdBackoff()) : null)
                .withDoneAt(held ? null : now));
        for (var merchantId : outcome.merchantIds()) {
            events.publishEvent(new MerchantDataErased(Ids.next(), now, merchantId, request.id()));
        }
        intake.record(
                request,
                Intake.SYSTEM,
                Intake.SYSTEM,
                "erasure_step",
                Map.of(
                        "module",
                        module,
                        "status",
                        held ? Step.HELD : Step.DONE,
                        "retained",
                        outcome.retained().size(),
                        "holds",
                        outcome.held().size()));
    }

    private void failed(String requestId, String module, RuntimeException e) {
        store.lockStep(requestId, module).ifPresent(step -> {
            var attempts = step.attempts() + 1;
            var backoff = Duration.ofMinutes(1L << Math.min(attempts, 9));
            store.saveStep(step.withStatus(Step.FAILED)
                    .withAttempts(attempts)
                    .withLastError(e.getClass().getSimpleName())
                    .withNextAttemptAt(
                            clock.instant().plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff)));
        });
    }

    /** Completes the request when no step is pending or failed; tracks the holds after that. */
    private boolean finish(String requestId) {
        return Boolean.TRUE.equals(tx.execute(_ -> {
            var request = store.lock(requestId).orElseThrow();
            var steps = store.steps(requestId);
            if (steps.isEmpty()
                    || steps.stream()
                            .anyMatch(s -> Step.PENDING.equals(s.status()) || Step.FAILED.equals(s.status()))) {
                return false;
            }
            var holds = (int)
                    steps.stream().filter(s -> Step.HELD.equals(s.status())).count();
            var now = clock.instant();
            if (request.state() == RequestState.IN_PROGRESS) {
                var done = request.withState(RequestState.COMPLETED)
                        .withCompletedAt(now)
                        .withHoldsOpen(holds);
                done = intake.save(holds == 0 ? done.withSealed(null) : done);
                intake.record(done, Intake.SYSTEM, Intake.SYSTEM, "erasure_completed", Map.of("holds", holds));
                events.publishEvent(new PersonalDataErased(Ids.next(), now, done.id(), done.subjectId(), holds));
                return true;
            }
            if (request.state() == RequestState.COMPLETED && request.holdsOpen() != holds) {
                var updated = request.withHoldsOpen(holds);
                updated = intake.save(holds == 0 ? updated.withSealed(null) : updated);
                if (holds == 0) {
                    intake.record(updated, Intake.SYSTEM, Intake.SYSTEM, "holds_cleared", Map.of("holds", 0));
                    events.publishEvent(new PersonalDataErased(Ids.next(), now, updated.id(), updated.subjectId(), 0));
                }
            }
            return false;
        }));
    }

    @Override
    public int sweep() {
        var now = clock.instant();
        var swept = 0;
        for (var request : store.expiredExports(now, 100)) {
            var key = request.exportKey();
            if (key == null) {
                continue;
            }
            storage.delete(key);
            tx.executeWithoutResult(_ -> store.lock(request.id()).ifPresent(r -> {
                var gone = intake.save(r.withExportKey(null).withLinkHash(null).withLinkExpiresAt(null));
                intake.record(gone, Intake.SYSTEM, Intake.SYSTEM, "export_deleted", Map.of("expired", true));
            }));
            swept++;
        }
        return swept;
    }

    private static boolean due(Request request, Instant now) {
        var at = request.scheduledFor();
        return (request.state() == RequestState.VERIFIED && at != null && !at.isAfter(now))
                || (request.state() == RequestState.IN_PROGRESS && request.type() == RequestType.ACCESS);
    }

    private static boolean stepDue(Step step, Instant now) {
        var at = step.nextAttemptAt();
        return !Step.DONE.equals(step.status()) && (at == null || !at.isAfter(now));
    }

    /** Whom the request is about, with the contact they had when asking (sealed in the request). */
    private Subject subject(Request request) {
        var content = secrets.open(request.id(), request.sealed());
        var locale = accounts.holder(request.subjectId())
                .map(h -> Locale.forLanguageTag(h.locale()))
                .orElse(Locale.CANADA);
        return new Subject(request.subjectId(), content.email(), content.phone(), locale);
    }
}
