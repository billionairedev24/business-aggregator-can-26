package ca.northline.privacy.application;

import ca.northline.identity.api.PersonDirectory;
import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.application.PrivacyRequests.DeskItem;
import ca.northline.privacy.application.PrivacyRequests.ExportView;
import ca.northline.privacy.application.PrivacyRequests.LawView;
import ca.northline.privacy.application.PrivacyRequests.RequestView;
import ca.northline.privacy.application.PrivacyRequests.StepView;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.region.api.PrivacyRegimes;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds what the person and staff see from a request row. */
@Component
@RequiredArgsConstructor
class Views {

    private final PrivacyRequestStore store;
    private final PrivacyRegimes regimes;
    private final PersonDirectory people;
    private final Secrets secrets;
    private final Clock clock;

    LawView law(Request r, Locale locale) {
        var regime = regimes.of(r.law(), r.province());
        return new LawView(
                regime.law(),
                regime.name(locale),
                regime.shortName(locale),
                regime.authority(locale),
                regime.authorityUrl(),
                regime.responseDays(),
                regime.businessDays(),
                regime.extensionDays());
    }

    /** {@code detail}: also what the person told us (unsealed) and the erasure's steps. */
    RequestView view(Request r, Locale locale, boolean detail) {
        var now = clock.instant();
        var content = detail ? secrets.open(r.id(), r.sealed()) : null;
        var phone = content == null ? null : content.phone();
        var codeSentTo = r.state() == RequestState.AWAITING_VERIFICATION && r.codeHash() != null && phone != null
                ? PrivacyRules.maskedPhone(phone)
                : null;
        var export = r.type() != RequestType.ACCESS || r.state() != RequestState.COMPLETED
                ? null
                : new ExportView(
                        r.exportKey() != null
                                && r.exportExpiresAt() != null
                                && r.exportExpiresAt().isAfter(now),
                        r.exportExpiresAt(),
                        r.exportBytes());
        var steps = detail && r.type() == RequestType.ERASURE
                ? store.steps(r.id()).stream()
                        .map(s ->
                                new StepView(s.module(), s.status(), s.attempts(), s.holds(), s.retained(), s.doneAt()))
                        .toList()
                : List.<StepView>of();
        return new RequestView(
                r.id(),
                PrivacyRules.reference(r.number()),
                r.type(),
                r.state(),
                r.subjectKind(),
                r.channel(),
                r.province(),
                law(r, locale),
                r.receivedAt(),
                r.dueAt(),
                r.extendedTo(),
                r.extensionReason(),
                r.overdue(now),
                r.verification(),
                r.verifiedAt(),
                codeSentTo,
                r.scheduledFor(),
                r.startedAt(),
                r.completedAt(),
                r.decision(),
                r.decisionNote(),
                export,
                content == null ? List.of() : content.corrections(),
                content == null ? null : content.note(),
                steps,
                r.holdsOpen());
    }

    List<DeskItem> items(List<Request> requests, Locale locale) {
        var names = people.people(requests.stream().map(Request::subjectId).toList());
        var now = clock.instant();
        return requests.stream()
                .map(r -> new DeskItem(
                        r.id(),
                        PrivacyRules.reference(r.number()),
                        r.type(),
                        r.state(),
                        r.subjectId(),
                        names.containsKey(r.subjectId())
                                ? names.get(r.subjectId()).displayName()
                                : "",
                        r.subjectKind(),
                        r.channel(),
                        r.province(),
                        law(r, locale).shortName(),
                        r.receivedAt(),
                        r.deadline(),
                        r.overdue(now),
                        r.holdsOpen()))
                .toList();
    }
}
