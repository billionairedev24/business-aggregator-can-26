package ca.northline.console.application;

import ca.northline.console.application.PilotOnboarding.Detail;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pilot onboarding's writes (S-120), each answered with the business's refreshed {@link Detail}. Staff need the
 * {@code onboard} action (merchant success, admin).
 */
public interface PilotActions {

    String NOT_STARTED = "The business hasn't created its account yet.";

    record Invited(Detail detail, String link, Instant expiresAt) {}

    Invited invite(PilotCohort.NewInvite command, PilotCohort.Actor actor);

    Invited reinvite(String pilotId, @Nullable String email, @Nullable String language, PilotCohort.Actor actor);

    Detail enrol(String businessId, String marketId, PilotCohort.Actor actor);

    Detail assign(String pilotId, @Nullable String ownerId, PilotCohort.Actor actor);

    Detail block(String pilotId, @Nullable String text, @Nullable String owner, PilotCohort.Actor actor);

    Detail note(String pilotId, String body, PilotCohort.Actor actor);

    Detail scheduleVisit(
            String pilotId,
            @Nullable Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName,
            PilotCohort.Actor actor);

    Detail addVisitPhoto(String pilotId, String visitId, KitchenVisits.Upload photo, PilotCohort.Actor actor);

    Detail recordVisit(
            String pilotId,
            String visitId,
            boolean passed,
            Map<String, String> checklist,
            @Nullable String note,
            PilotCohort.Actor actor);

    Detail cancelVisit(String pilotId, String visitId, PilotCohort.Actor actor);

    KitchenVisits.Upload visitPhoto(String pilotId, String visitId, String photoId);
}
