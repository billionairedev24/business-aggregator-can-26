package ca.northline.console.application;

import ca.northline.console.application.PilotOnboarding.PilotDetail;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pilot onboarding's writes (S-120), each answered with the business's refreshed {@link PilotDetail}. Staff need the
 * {@code onboard} action (merchant success, admin).
 */
public interface PilotActions {

    String NOT_STARTED = "The business hasn't created its account yet.";

    record PilotInvited(PilotDetail detail, String link, Instant expiresAt) {}

    PilotInvited invite(PilotCohort.NewInvite command, PilotCohort.Actor actor);

    PilotInvited reinvite(String pilotId, @Nullable String email, @Nullable String language, PilotCohort.Actor actor);

    PilotDetail enrol(String businessId, String marketId, PilotCohort.Actor actor);

    PilotDetail assign(String pilotId, @Nullable String ownerId, PilotCohort.Actor actor);

    PilotDetail block(String pilotId, @Nullable String text, @Nullable String owner, PilotCohort.Actor actor);

    PilotDetail note(String pilotId, String body, PilotCohort.Actor actor);

    PilotDetail scheduleVisit(
            String pilotId,
            @Nullable Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName,
            PilotCohort.Actor actor);

    PilotDetail addVisitPhoto(String pilotId, String visitId, KitchenVisits.Upload photo, PilotCohort.Actor actor);

    PilotDetail recordVisit(
            String pilotId,
            String visitId,
            boolean passed,
            Map<String, String> checklist,
            @Nullable String note,
            PilotCohort.Actor actor);

    PilotDetail cancelVisit(String pilotId, String visitId, PilotCohort.Actor actor);

    KitchenVisits.Upload visitPhoto(String pilotId, String visitId, String photoId);
}
