package ca.northline.merchants.integration;

import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.merchants.application.IdentityVerification;
import ca.northline.merchants.application.OwnerIdentity.ApplyIdentitySession;
import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.IdentitySessionState;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code northline.identity.provider=local} (and the {@code test} profile): Stripe Identity without Stripe. A session's
 * "hosted flow" is a page of the api ({@code /api/v1/dev/identity-sessions/{id}}, {@code local} profile only) where the
 * developer picks the outcome — verified, a name or date-of-birth mismatch, one of Stripe's {@code requires_input}
 * errors, still processing, or canceled — which is applied exactly as a webhook would be. Sessions live in memory.
 */
@Slf4j
class FakeIdentityVerification implements IdentityVerification, DevIdentityOutcomes {

    private record Planned(
            IdentitySessionState state, @Nullable String lastError, IdentityMatch name, IdentityMatch dob) {}

    private static final Map<String, Planned> OUTCOMES = outcomesInOrder();

    private record FakeSession(String returnUrl, @Nullable Planned planned) {}

    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<>();
    private final String apiUrl;
    private final ObjectProvider<ApplyIdentitySession> apply;
    private final Clock clock;

    FakeIdentityVerification(String apiUrl, ObjectProvider<ApplyIdentitySession> apply, Clock clock) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apply = apply;
        this.clock = clock;
    }

    @Override
    public Session start(StartRequest request) {
        var id = "vs_fake_" + Ids.next();
        sessions.put(id, new FakeSession(request.returnUrl(), null));
        log.info(
                "FAKE Stripe Identity session {} for owner {} of {}: pick the outcome at {}",
                id,
                request.principalId(),
                request.merchantId(),
                url(id));
        return new Session(id, url(id));
    }

    @Override
    public void cancel(String sessionId) {
        sessions.computeIfPresent(sessionId, (_, s) -> new FakeSession(s.returnUrl(), OUTCOMES.get("canceled")));
    }

    /** Unknown or undecided sessions read as verified with a matching name (the happy path). */
    @Override
    public SessionResult read(String sessionId, Expected expected) {
        var session = sessions.get(sessionId);
        var planned = session == null || session.planned() == null ? verified() : session.planned();
        return new SessionResult(planned.state(), planned.lastError(), planned.name(), planned.dob());
    }

    @Override
    public List<String> outcomes() {
        return List.copyOf(OUTCOMES.keySet());
    }

    @Override
    public Optional<String> prepare(String sessionId, String outcome) {
        var planned = OUTCOMES.get(outcome);
        var session = sessions.get(sessionId);
        if (planned == null || session == null) {
            return Optional.empty();
        }
        sessions.put(sessionId, new FakeSession(session.returnUrl(), planned));
        return Optional.of(session.returnUrl());
    }

    @Override
    public Optional<String> finish(String sessionId, String outcome) {
        var back = prepare(sessionId, outcome);
        back.ifPresent(_ -> {
            var planned = Objects.requireNonNull(OUTCOMES.get(outcome));
            apply.getObject()
                    .apply(new ApplyIdentitySession.Update(
                            sessionId, planned.state(), planned.lastError(), clock.instant()));
        });
        return back;
    }

    private static Planned verified() {
        return Objects.requireNonNull(OUTCOMES.get("verified"));
    }

    private String url(String sessionId) {
        return apiUrl + "/api/v1/dev/identity-sessions/" + sessionId;
    }

    private static Map<String, Planned> outcomesInOrder() {
        var none = IdentityMatch.UNAVAILABLE;
        var m = new LinkedHashMap<String, Planned>();
        m.put("verified", new Planned(IdentitySessionState.VERIFIED, null, IdentityMatch.MATCH, none));
        m.put("name_mismatch", new Planned(IdentitySessionState.VERIFIED, null, IdentityMatch.MISMATCH, none));
        m.put(
                "dob_mismatch",
                new Planned(IdentitySessionState.VERIFIED, null, IdentityMatch.MATCH, IdentityMatch.MISMATCH));
        m.put("processing", new Planned(IdentitySessionState.PROCESSING, null, none, none));
        for (var code :
                List.of("document_unverified_other", "document_expired", "selfie_face_mismatch", "consent_declined")) {
            m.put(code, new Planned(IdentitySessionState.REQUIRES_INPUT, code, none, none));
        }
        m.put("canceled", new Planned(IdentitySessionState.CANCELED, null, none, none));
        return java.util.Collections.unmodifiableMap(m);
    }
}
