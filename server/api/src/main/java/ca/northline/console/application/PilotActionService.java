package ca.northline.console.application;

import ca.northline.console.application.PilotOnboarding.Detail;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PilotActions} over the merchants module's {@link PilotCohort} and {@link KitchenVisits}. */
@Service
@RequiredArgsConstructor
@Transactional
class PilotActionService implements PilotActions {

    private final PilotCohort cohort;
    private final KitchenVisits visits;
    private final PilotOnboarding board;

    @Override
    public Invited invite(PilotCohort.NewInvite command, PilotCohort.Actor actor) {
        var invited = cohort.invite(command, actor);
        return new Invited(board.detail(invited.pilot().id()), invited.link(), invited.expiresAt());
    }

    @Override
    public Invited reinvite(
            String pilotId, @Nullable String email, @Nullable String language, PilotCohort.Actor actor) {
        var invited = cohort.reinvite(pilotId, email, language == null ? "en" : language, actor);
        return new Invited(board.detail(pilotId), invited.link(), invited.expiresAt());
    }

    @Override
    public Detail enrol(String businessId, String marketId, PilotCohort.Actor actor) {
        return board.detail(cohort.enrol(businessId, marketId, actor).id());
    }

    @Override
    public Detail assign(String pilotId, @Nullable String ownerId, PilotCohort.Actor actor) {
        cohort.assign(pilotId, ownerId, actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail block(String pilotId, @Nullable String text, @Nullable String owner, PilotCohort.Actor actor) {
        cohort.block(pilotId, text, owner, actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail note(String pilotId, String body, PilotCohort.Actor actor) {
        cohort.note(pilotId, body, actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail scheduleVisit(
            String pilotId,
            @Nullable Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName,
            PilotCohort.Actor actor) {
        visits.schedule(new KitchenVisits.Schedule(business(pilotId), at, inspectorId, inspectorName), actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail addVisitPhoto(String pilotId, String visitId, KitchenVisits.Upload photo, PilotCohort.Actor actor) {
        visits.addPhoto(business(pilotId), visitId, photo, actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail recordVisit(
            String pilotId,
            String visitId,
            boolean passed,
            Map<String, String> checklist,
            @Nullable String note,
            PilotCohort.Actor actor) {
        visits.record(business(pilotId), visitId, passed, checklist, note, actor);
        return board.detail(pilotId);
    }

    @Override
    public Detail cancelVisit(String pilotId, String visitId, PilotCohort.Actor actor) {
        visits.cancel(business(pilotId), visitId, actor);
        return board.detail(pilotId);
    }

    @Override
    @Transactional(readOnly = true)
    public KitchenVisits.Upload visitPhoto(String pilotId, String visitId, String photoId) {
        return visits.photo(business(pilotId), visitId, photoId);
    }

    private String business(String pilotId) {
        var pilot = cohort.find(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
        var merchantId = pilot.merchantId();
        if (merchantId == null) {
            throw new Conflict("pilot_not_started", NOT_STARTED);
        }
        return merchantId;
    }
}
