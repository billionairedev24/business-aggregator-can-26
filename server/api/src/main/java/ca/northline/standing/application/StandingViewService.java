package ca.northline.standing.application;

import ca.northline.payments.api.DisputeAppeals;
import ca.northline.standing.application.StandingUseCases.AppealView;
import ca.northline.standing.application.StandingUseCases.BusinessStanding;
import ca.northline.standing.application.StandingUseCases.CoachingView;
import ca.northline.standing.application.StandingUseCases.ConsequenceView;
import ca.northline.standing.application.StandingUseCases.CustomerStanding;
import ca.northline.standing.application.StandingUseCases.DisputeView;
import ca.northline.standing.application.StandingUseCases.FileView;
import ca.northline.standing.application.StandingUseCases.TaskView;
import ca.northline.standing.application.StandingUseCases.ViewStanding;
import ca.northline.standing.domain.Appeal;
import ca.northline.standing.domain.Consequence;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ViewStanding}: what a business (Studio, console) or a customer (account) sees of its standing. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class StandingViewService implements ViewStanding {

    static final int HISTORY = 100;

    private final ConsequenceStore consequences;
    private final AppealStore appeals;
    private final CoachingStore coaching;
    private final DisputeAppeals disputes;
    private final Clock clock;

    @Override
    public BusinessStanding business(String merchantId) {
        var now = clock.instant();
        var filed = appeals.ofBusiness(merchantId, HISTORY);
        var labels = disputes.appealable(merchantId, now).stream()
                .collect(Collectors.toMap(DisputeAppeals.Facts::disputeId, Function.identity()));
        var appealViews = filed.stream()
                .map(a -> view(
                        a,
                        a.subjectType() == Appeal.Subject.DISPUTE
                                ? disputes.facts(merchantId, a.subjectId())
                                        .map(DisputeAppeals.Facts::caseNumber)
                                        .orElse(null)
                                : null))
                .toList();
        var byConsequence = appealViews.stream()
                .filter(a -> "consequence".equals(a.subjectType()))
                .collect(Collectors.toMap(AppealView::subjectId, Function.identity(), (a, _) -> a));
        var appealedDisputes = filed.stream()
                .filter(a -> a.subjectType() == Appeal.Subject.DISPUTE)
                .map(Appeal::subjectId)
                .collect(Collectors.toSet());
        var all = consequences.ofBusiness(merchantId, HISTORY).stream()
                .map(c -> view(c, byConsequence.get(c.id()), now))
                .toList();
        var plans = coaching.ofBusiness(merchantId, 20);
        return new BusinessStanding(
                all.stream().filter(ConsequenceView::active).toList(),
                all.stream().filter(c -> !c.active()).toList(),
                appealViews,
                labels.values().stream()
                        .filter(f -> !appealedDisputes.contains(f.disputeId()))
                        .map(StandingViewService::dispute)
                        .toList(),
                plans.stream()
                        .filter(p -> "active".equals(p.state()))
                        .findFirst()
                        .map(StandingViewService::view)
                        .orElse(null),
                plans.stream()
                        .filter(p -> !"active".equals(p.state()))
                        .map(StandingViewService::view)
                        .toList());
    }

    @Override
    public CustomerStanding customer(String userId) {
        var now = clock.instant();
        var all = consequences.ofCustomer(userId, HISTORY).stream()
                .map(c -> view(c, null, now))
                .toList();
        return new CustomerStanding(
                all.stream().filter(ConsequenceView::active).toList(),
                all.stream().filter(c -> !c.active()).toList());
    }

    static ConsequenceView view(Consequence c, @Nullable AppealView appeal, Instant now) {
        var appealable = appeal == null && c.appealableAt(now);
        return new ConsequenceView(
                c.id(),
                c.kind().code(),
                c.cause(),
                c.reason(),
                c.detail(),
                c.startedAt(),
                c.endsAt(),
                c.active(),
                c.liftedAt(),
                c.liftReason(),
                c.liftedBy() != null && !Consequence.SYSTEM.equals(c.liftedBy()),
                appealable,
                c.kind().appealable() && c.merchantId() != null ? c.appealBy() : null,
                appeal);
    }

    static AppealView view(Appeal a, @Nullable String label) {
        return new AppealView(
                a.id(),
                a.number(),
                a.subjectType().code(),
                a.subjectId(),
                a.subjectKind(),
                label,
                a.statement(),
                a.state().code(),
                a.filedAt(),
                a.decidedAt(),
                a.decisionReason(),
                a.compensationCents(),
                a.files().stream()
                        .map(f -> new FileView(f.id(), f.name(), f.contentType(), f.size()))
                        .toList());
    }

    static CoachingView view(CoachingStore.Plan p) {
        return new CoachingView(
                p.id(),
                p.title(),
                p.note(),
                p.dueOn(),
                p.state(),
                p.createdAt(),
                p.closedAt(),
                p.closeReason(),
                p.tasks().stream()
                        .map(t -> new TaskView(t.id(), t.title(), t.doneAt() != null, t.doneAt()))
                        .toList());
    }

    static DisputeView dispute(DisputeAppeals.Facts f) {
        var decided = Objects.requireNonNull(f.decidedAt());
        return new DisputeView(
                f.disputeId(),
                f.caseNumber(),
                f.subject(),
                Objects.requireNonNull(f.decision()),
                f.refundCents(),
                decided,
                decided.plus(DisputeAppeals.WINDOW),
                f.note());
    }
}
