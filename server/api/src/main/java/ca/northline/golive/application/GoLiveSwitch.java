package ca.northline.golive.application;

import ca.northline.golive.application.GoLiveChecklist.Actor;
import ca.northline.golive.application.GoLiveChecklist.Checklist;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-118: switching a market between {@code pilot} and {@code live}. Launch is two-person: {@link #request} by one admin,
 * {@link #approve} by another (both audited), refused while a required gate doesn't clear unless the request carries an
 * emergency override with its reason. {@link #rollback} is one admin with a reason: the market is {@code pilot} again and
 * hidden from new public discovery; orders and bookings in progress carry on. {@link #startHypercare} plans the 14 days
 * after launch on top of the on-call rota.
 */
public interface GoLiveSwitch {

    String CONFIRM = "Type the market's name to confirm.";
    String NOT_PILOT = "Only a market in pilot can go live.";
    String NOT_LIVE = "This market isn't live.";
    String NOT_READY = "Every required gate must pass before going live, or ask with an emergency override.";
    String PENDING = "A launch request already waits for a second admin.";
    String ABOVE_PROVINCE = "A market can't be more open than its province.";
    String NO_ZONE = "Add a delivery zone with a boundary before the market goes live.";
    String ROLLBACK_REASON = "Give the reason for the rollback, 10 to 500 characters.";
    String HYPERCARE_NOT_LIVE = "Hypercare starts once the market is live.";
    String HYPERCARE_EXISTS =
            "This market already has a hypercare rota for those days. Change a day from the On-call screen.";
    String HYPERCARE_START = "Start hypercare today or within the next 14 days.";
    String NOT_STAFF = "Choose someone on the Northline team.";

    /** @param overrideReason non-blank = an emergency override (20–500 characters) */
    Checklist request(String marketId, @Nullable String note, @Nullable String overrideReason, Actor actor);

    /** @param confirm the market's name */
    Checklist approve(String marketId, String requestId, String confirm, Actor actor);

    /** Withdrawn by its requester, rejected by another admin. */
    Checklist close(String marketId, String requestId, @Nullable String reason, Actor actor);

    Checklist rollback(String marketId, String reason, String confirm, Actor actor);

    /** @param startsOn the market's local date of day 1; null = today there */
    Checklist startHypercare(
            String marketId,
            @Nullable LocalDate startsOn,
            List<String> primaries,
            List<String> secondaries,
            List<String> businessContacts,
            Actor actor);
}
