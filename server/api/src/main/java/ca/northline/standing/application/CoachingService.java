package ca.northline.standing.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.messaging.api.StandingNotice;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.standing.application.StandingUseCases.CoachingView;
import ca.northline.standing.application.StandingUseCases.ConsequenceView;
import ca.northline.standing.application.StandingUseCases.ManageStanding;
import ca.northline.standing.application.StandingUseCases.TickCoachingTask;
import ca.northline.standing.domain.AppealRules;
import ca.northline.standing.domain.CoachingRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Console: lift a consequence; start and close a coaching plan (design 03 "Coaching plan"). Studio: tick its tasks.
 * One active plan per business; the owners are emailed when it starts. Audited.
 */
@Service
@RequiredArgsConstructor
@Transactional
class CoachingService implements ManageStanding, TickCoachingTask {

    private final CoachingStore store;
    private final ConsequenceService consequences;
    private final MerchantPlaces places;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public ConsequenceView lift(String consequenceId, String reason, String staffId, String role) {
        return consequences.lift(consequenceId, reason, staffId, role, null);
    }

    @Override
    public CoachingView startCoaching(
            String merchantId,
            @Nullable String title,
            @Nullable String note,
            @Nullable LocalDate dueOn,
            @Nullable List<String> tasks,
            String staffId,
            String role) {
        var zone = places.of(merchantId).zone();
        var draft = CoachingRules.Draft.validate(title, note, dueOn, tasks, LocalDate.now(clock.withZone(zone)));
        var now = clock.instant();
        var id = Ids.next();
        var list = new ArrayList<CoachingStore.Task>();
        for (int i = 0; i < draft.tasks().size(); i++) {
            list.add(new CoachingStore.Task(Ids.next(), i + 1, draft.tasks().get(i), null, null));
        }
        var plan = new CoachingStore.Plan(
                id,
                merchantId,
                draft.title(),
                draft.note(),
                draft.dueOn(),
                "active",
                staffId,
                role,
                now,
                null,
                null,
                list);
        if (!store.insert(plan)) {
            throw new Conflict("coaching_active", CoachingRules.ACTIVE);
        }
        audit.record(new AuditTrail.Entry(
                merchantId,
                staffId,
                role,
                "standing.coaching_started",
                "coaching_plan",
                id,
                null,
                Map.of("tasks", list.size())));
        events.publishEvent(new StandingNotice(
                Ids.next(),
                now,
                id,
                merchantId,
                "coaching_started",
                null,
                draft.note() == null ? draft.title() : draft.title() + " — " + draft.note(),
                null,
                0));
        return StandingViewService.view(plan);
    }

    @Override
    public CoachingView closeCoaching(
            String planId, boolean completed, @Nullable String reason, String staffId, String role) {
        var plan = store.find(planId).orElseThrow(() -> new NotFound("coaching_plan", planId));
        if (!"active".equals(plan.state())) {
            throw new Conflict("plan_closed", CoachingRules.CLOSED);
        }
        var why = reason == null || reason.isBlank() ? null : AppealRules.reason(reason);
        store.close(planId, completed ? "completed" : "cancelled", staffId, why, clock.instant());
        audit.record(new AuditTrail.Entry(
                plan.merchantId(),
                staffId,
                role,
                completed ? "standing.coaching_completed" : "standing.coaching_cancelled",
                "coaching_plan",
                planId,
                null,
                null));
        return StandingViewService.view(store.find(planId).orElseThrow());
    }

    @Override
    public CoachingView tick(String merchantId, String planId, String taskId, boolean done, String userId) {
        var plan = store.find(planId)
                .filter(p -> p.merchantId().equals(merchantId))
                .orElseThrow(() -> new NotFound("coaching_plan", planId));
        if (!"active".equals(plan.state())) {
            throw new Conflict("plan_closed", CoachingRules.CLOSED);
        }
        if (!store.tick(planId, taskId, done, userId, clock.instant())) {
            throw new NotFound("task", taskId);
        }
        return StandingViewService.view(store.find(planId).orElseThrow());
    }
}
