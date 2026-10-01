package ca.northline.availability.application;

import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.CalendarLinkState;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.SlotPlanner.Slot;
import ca.northline.availability.domain.TimeOff;
import ca.northline.availability.domain.TimeRange;
import ca.northline.availability.domain.WeeklyHours;
import ca.northline.catalogue.api.CatalogueFacts.ServiceDuration;
import ca.northline.region.api.Holiday;
import ca.northline.shared.security.MerchantRole;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Availability use cases (one method each) and their read models. */
public final class AvailabilityUseCases {
    private AvailabilityUseCases() {}

    /** A team member who can take jobs (every role except bookkeeper). */
    public record Member(String userId, String name, MerchantRole role, boolean bookable) {}

    // ── Weekly hours ─────────────────────────────────────────────────────────────

    public record MemberHours(Member member, @Nullable LocalDate effectiveFrom, WeeklyHours hours) {}

    public record HoursView(
            List<MemberHours> members, @Nullable Instant lastSavedAt) {}

    public interface ViewHours {
        HoursView view(String merchantId);
    }

    /** "Save hours" for one or more members, applied from {@code effectiveFrom}. Existing bookings are never moved. */
    public interface SaveHours {
        record MemberDays(String memberUserId, WeeklyHours hours) {}

        record Command(String merchantId, String actorId, LocalDate effectiveFrom, List<MemberDays> members) {}

        HoursView save(SaveHours.Command command);
    }

    /**
     * The live preview: bookable start times for a job of {@code durationMin} on {@code date}. {@code ranges},
     * {@code intervalMin} and {@code bufferMin} override the saved values (unsaved edits).
     */
    public interface PreviewSlots {
        record Query(
                String merchantId,
                String memberUserId,
                LocalDate date,
                int durationMin,
                @Nullable List<TimeRange> ranges,
                @Nullable Integer intervalMin,
                @Nullable Integer bufferMin) {}

        /**
         * {@code closed}: {@code time_off} or {@code holiday} when the day is closed, otherwise null. {@code busyBlocks}:
         * busy times from the member's connected calendars that day (S-32).
         */
        record Preview(
                List<Slot> slots,
                int jobs,
                int busyBlocks,
                int intervalMin,
                int bufferMin,
                @Nullable String closed) {}

        Preview preview(Query query);
    }

    /** Services to preview with (catalogue), with durations. */
    public interface ListPreviewServices {
        List<ServiceDuration> list(String merchantId, String lang);
    }

    // ── Booking rules ────────────────────────────────────────────────────────────

    public record RulesView(
            BookingRules rules,
            List<String> zones,
            @Nullable Instant lastSavedAt) {}

    public interface ViewRules {
        RulesView rules(String merchantId);
    }

    public interface SaveRules {
        RulesView saveRules(String merchantId, String actorId, BookingRules rules);
    }

    // ── Time off & holidays ─────────────────────────────────────────────────────

    public record TimeOffEntry(TimeOff timeOff, @Nullable String memberName) {}

    public record HolidayView(Holiday holiday, boolean open) {}

    /** @param provinceNameEn / {@code provinceNameFr}: the province whose holidays these are ("" when none is known) */
    public record TimeOffView(
            List<TimeOffEntry> entries,
            List<HolidayView> holidays,
            long holidayPremiumCents,
            String provinceNameEn,
            String provinceNameFr) {}

    public interface ViewTimeOff {
        TimeOffView view(String merchantId);
    }

    public interface AddTimeOff {
        record Command(
                String merchantId,
                String actorId,
                @Nullable String memberUserId,
                @Nullable LocalDate startsOn,
                @Nullable LocalDate endsOn,
                TimeOff.Kind kind,
                List<TimeRange> specialRanges,
                @Nullable String reason) {}

        TimeOffEntry add(AddTimeOff.Command command);
    }

    public interface RemoveTimeOff {
        void remove(String merchantId, String actorId, String timeOffId);
    }

    /** "2 existing bookings fall in this range" — jobs of the member (or the whole team) on those days. */
    public interface CountConflicts {
        int count(String merchantId, @Nullable String memberUserId, LocalDate from, LocalDate to);
    }

    public interface SetHolidayOpen {
        HolidayView set(String merchantId, String actorId, LocalDate date, boolean open);
    }

    // ── Calendar sync & team ────────────────────────────────────────────────────

    /**
     * {@code feedUrl} only for iCal. Google / Outlook: {@code state} once linked ({@code reconnect} = the grant was
     * revoked), {@code available} = the provider is configured here, {@code sources} = the calendars that block slots,
     * {@code authorizationUrl} right after "Connect": send the browser there (OAuth consent).
     */
    public record CalendarView(
            CalendarProvider provider,
            boolean connected,
            @Nullable String accountLabel,
            @Nullable Instant lastSyncAt,
            @Nullable String feedUrl,
            @Nullable CalendarLinkState state,
            boolean available,
            List<String> sources,
            @Nullable URI authorizationUrl) {
        public CalendarView {
            sources = List.copyOf(sources);
        }
    }

    public record TeamMemberView(Member member, @Nullable WeeklyHours hours) {}

    public record SyncView(List<CalendarView> calendars, List<TeamMemberView> team) {}

    /** The signed-in member's calendars and the team's bookability. */
    public interface ViewSync {
        SyncView view(String merchantId, String userId);
    }

    /** iCal: connects at once. Google / Outlook: starts OAuth — the view carries the consent page URL. */
    public interface ConnectCalendar {
        CalendarView connect(String merchantId, String userId, CalendarProvider provider);
    }

    public interface DisconnectCalendar {
        CalendarView disconnect(String merchantId, String userId, CalendarProvider provider);
    }

    public interface SetBookable {
        TeamMemberView set(String merchantId, String actorId, String memberUserId, boolean bookable);
    }
}
