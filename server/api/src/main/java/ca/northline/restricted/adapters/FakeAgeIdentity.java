package ca.northline.restricted.adapters;

import ca.northline.restricted.application.AgeIdentityProvider;
import ca.northline.restricted.application.AgeVerificationUseCases.ApplyAgeSession;
import ca.northline.restricted.application.DevAgeOutcomes;
import ca.northline.shared.Ids;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code northline.age-verification.provider=local} (and the {@code test} profile): an identity provider without Stripe.
 * The "hosted flow" is a page of the api ({@code /api/v1/dev/age-sessions/{id}}, {@code local} only) where the
 * developer picks the outcome — an age, under age, or one of Stripe's {@code requires_input} errors — applied as the
 * webhook would be. Sessions live in memory; an undecided session is still processing.
 */
@Slf4j
class FakeAgeIdentity implements AgeIdentityProvider, DevAgeOutcomes {

    private record Planned(
            String state,
            @Nullable String lastError,
            @Nullable Integer age) {}

    private static final Map<String, Planned> OUTCOMES = outcomes0();

    private record FakeSession(String returnUrl, @Nullable Planned planned) {}

    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<>();
    private final String apiUrl;
    private final ObjectProvider<ApplyAgeSession> apply;

    FakeAgeIdentity(String apiUrl, ObjectProvider<ApplyAgeSession> apply) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apply = apply;
    }

    @Override
    public Session start(StartRequest request) {
        var id = "vs_fake_age_" + Ids.next();
        sessions.put(id, new FakeSession(request.returnUrl(), null));
        log.info("FAKE age check session {}: pick the outcome at {}", id, url(id));
        return new Session(id, url(id));
    }

    @Override
    public Result read(String sessionId, LocalDate today) {
        var session = sessions.get(sessionId);
        var planned = session == null || session.planned() == null
                ? new Planned("processing", null, null)
                : session.planned();
        return new Result(planned.state(), planned.lastError(), planned.age());
    }

    @Override
    public void redact(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public String method() {
        return "fake";
    }

    @Override
    public List<String> outcomes() {
        return List.copyOf(OUTCOMES.keySet());
    }

    @Override
    public Optional<String> finish(String sessionId, String outcome) {
        var planned = OUTCOMES.get(outcome);
        var session = sessions.get(sessionId);
        if (planned == null || session == null) {
            return Optional.empty();
        }
        sessions.put(sessionId, new FakeSession(session.returnUrl(), planned));
        apply.getObject().apply(sessionId, planned.state(), planned.lastError());
        return Optional.of(session.returnUrl());
    }

    private String url(String sessionId) {
        return apiUrl + "/api/v1/dev/age-sessions/" + sessionId;
    }

    private static Map<String, Planned> outcomes0() {
        var m = new LinkedHashMap<String, Planned>();
        m.put("age_30", new Planned("verified", null, 30));
        m.put("age_19", new Planned("verified", null, 19));
        m.put("age_18", new Planned("verified", null, 18));
        m.put("age_16", new Planned("verified", null, 16));
        for (var code : List.of("document_expired", "document_unverified_other", "selfie_face_mismatch")) {
            m.put(code, new Planned("requires_input", code, null));
        }
        m.put("canceled", new Planned("canceled", null, null));
        return Collections.unmodifiableMap(m);
    }
}
