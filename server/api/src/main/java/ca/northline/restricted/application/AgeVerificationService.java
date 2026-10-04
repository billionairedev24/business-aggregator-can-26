package ca.northline.restricted.application;

import ca.northline.region.api.AgeRules;
import ca.northline.region.api.Regions;
import ca.northline.restricted.api.AgeVerifications;
import ca.northline.restricted.application.AgeVerificationStore.Row;
import ca.northline.restricted.application.AgeVerificationUseCases.ApplyAgeSession;
import ca.northline.restricted.application.AgeVerificationUseCases.ReadAgeCheck;
import ca.northline.restricted.application.AgeVerificationUseCases.StartAgeCheck;
import ca.northline.restricted.application.AgeVerificationUseCases.Started;
import ca.northline.restricted.domain.AgeMessages;
import ca.northline.restricted.domain.AgeRecord;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's age check. Started once (a new session replaces an open one), finished by the provider's webhook or
 * the local fake; a verified result keeps only "over N, on date, by method" — N capped at the strictest age any
 * province asks ({@link AgeRules#highestMinimumAge}) — and the provider is asked to redact the session. "Today" is the
 * platform zone's date (an age changes on a birthday; a day's difference across zones is immaterial here).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AgeVerificationService implements AgeVerifications, StartAgeCheck, ReadAgeCheck, ApplyAgeSession {

    static final String PENDING = Status.PENDING;
    static final String VERIFIED = Status.VERIFIED;
    static final String FAILED = Status.FAILED;

    private final AgeVerificationStore store;
    private final AgeIdentityProvider provider;
    private final AgeVerificationProperties properties;
    private final AgeRules rules;
    private final Regions regions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Status status(String userId) {
        return store.find(userId).map(this::view).orElseGet(Status::none);
    }

    @Override
    public Started start(String userId, String returnTo) {
        var returnUrl = switch (returnTo) {
            case "web" -> properties.webReturnUrl("/cart?age=done");
            case "web_food" -> properties.webReturnUrl("/food/checkout?age=done");
            case "app" -> properties.appReturnUrl();
            default -> throw RuleViolation.of("returnTo", "required", AgeMessages.RETURN_URL);
        };
        var now = clock.instant();
        var existing = store.find(userId).orElse(null);
        if (existing != null && VERIFIED.equals(existing.state())) {
            var floor = view(existing).ageFloor();
            if (floor >= rules.highestMinimumAge()) {
                throw new Conflict("age_already_verified", AgeMessages.ALREADY);
            }
        }
        var attempts = existing == null ? 0 : existing.attempts();
        if (attempts >= AgeMessages.MAX_ATTEMPTS) {
            throw new Conflict("age_check_attempts", AgeMessages.TOO_MANY);
        }
        var session = provider.start(new AgeIdentityProvider.StartRequest(userId, attempts + 1, returnUrl));
        var row = new Row(
                userId,
                PENDING,
                null,
                null,
                null,
                session.id(),
                null,
                attempts + 1,
                existing == null ? now : existing.startedAt(),
                now);
        store.save(row);
        return new Started(session.url(), view(row));
    }

    @Override
    public Status read(String userId) {
        var row = store.find(userId).orElse(null);
        if (row == null) {
            return Status.none();
        }
        var session = row.sessionId();
        if (PENDING.equals(row.state()) && session != null) {
            try {
                var result = provider.read(session, today());
                return view(applyResult(row, session, result));
            } catch (Conflict e) {
                log.info("Age check session of {} not read now: {}", userId, e.getCode());
            }
        }
        return view(row);
    }

    @Override
    public void apply(String sessionId, @Nullable String status, @Nullable String lastError) {
        var row = store.lockBySession(sessionId).orElse(null);
        if (row == null) {
            log.debug("Identity session {} is not an open age check; ignored", sessionId);
            return;
        }
        if ("verified".equals(status)) {
            applyResult(row, sessionId, provider.read(sessionId, today()));
        } else {
            applyResult(row, sessionId, new AgeIdentityProvider.Result(status == null ? "" : status, lastError, null));
        }
    }

    /** Moves a pending row on a provider result; a still-open session leaves it pending. */
    private Row applyResult(Row row, String sessionId, AgeIdentityProvider.Result result) {
        var now = clock.instant();
        var next = switch (result.state()) {
            case "verified" -> {
                var age = result.age();
                if (age == null) {
                    yield failed(row, "age_unavailable", now);
                }
                var kept = AgeRecord.capped(age, rules.highestMinimumAge());
                yield new Row(
                        row.userId(),
                        VERIFIED,
                        kept,
                        today(),
                        provider.method(),
                        null,
                        null,
                        row.attempts(),
                        row.startedAt(),
                        now);
            }
            case "canceled" -> failed(row, result.lastError() == null ? "canceled" : result.lastError(), now);
            case "requires_input" -> result.lastError() == null ? row : failed(row, result.lastError(), now);
            default -> row;
        };
        if (!next.equals(row)) {
            store.save(next);
            if (VERIFIED.equals(next.state())) {
                provider.redact(sessionId);
            }
        }
        return next;
    }

    private static Row failed(Row row, String error, Instant now) {
        var code = error.matches("[a-z_]{1,60}") ? error : "other";
        return new Row(row.userId(), FAILED, null, null, null, null, code, row.attempts(), row.startedAt(), now);
    }

    private Status view(Row row) {
        var over = row.overAge();
        var on = row.verifiedOn();
        var floor = VERIFIED.equals(row.state()) && over != null && on != null ? AgeRecord.floor(over, on, today()) : 0;
        return new Status(row.state(), over, on, floor, row.method(), row.lastError());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), regions.platformZone());
    }
}
