package ca.northline.availability.web;

import ca.northline.availability.application.CalendarUseCases.ChooseCalendarSources;
import ca.northline.availability.domain.BookingRules.AcceptMode;
import ca.northline.availability.domain.CalendarLinkState;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.TimeOff;
import ca.northline.shared.security.MerchantRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request and response records of the Availability screen. Hours are {@code {"mon": [["07:00","18:00"]], …}}; a missing
 * or empty day is "Not bookable".
 */
final class AvailabilityDtos {
    private AvailabilityDtos() {}

    static final String DAY_KEYS = "mon, tue, wed, thu, fri, sat, sun";

    // ── hours ──
    record MemberHoursResponse(
            String userId,
            String name,
            MerchantRole role,
            boolean bookable,
            @Nullable LocalDate effectiveFrom,
            Map<String, List<List<String>>> days) {}

    record HoursResponse(
            List<MemberHoursResponse> members, @Nullable Instant lastSavedAt) {}

    record MemberDaysBody(
            @NotNull(message = "Choose a team member.") String memberUserId,
            @NotNull(message = "Add the hours.") Map<String, List<List<String>>> days) {}

    record SaveHoursBody(
            @NotNull(message = "Pick today or a later date.")
            LocalDate effectiveFrom,

            @NotEmpty(message = "Choose a team member.") List<@Valid @NotNull MemberDaysBody> members) {}

    record PreviewBody(
            @NotNull(message = "Choose a team member.") String memberUserId,
            @NotNull(message = "Pick a day.") LocalDate date,

            @NotNull(message = "Choose a service.")
            @Min(value = 5, message = "Choose a service.")
            @Max(value = 1440, message = "Choose a service.")
            Integer durationMin,

            @Nullable List<List<String>> ranges,
            @Nullable Integer intervalMin,
            @Nullable Integer bufferMin) {}

    record SlotResponse(String start, boolean free) {}

    record PreviewResponse(
            List<SlotResponse> slots,
            int jobs,
            int busyBlocks,
            int intervalMin,
            int bufferMin,
            @Nullable String closed) {}

    record ServiceResponse(String id, String name, int durationMin) {}

    // ── rules ──
    record RulesBody(
            @NotNull(message = "Choose one of the options.") Integer intervalMin,
            @NotNull(message = "Choose one of the options.") Integer bufferMin,
            @NotNull(message = "Choose one of the options.") Integer minNoticeMin,
            @Nullable Integer sameDayCutoffMin,
            @NotNull(message = "Choose one of the options.") Integer horizonDays,
            @NotNull(message = "Choose one of the options.") Integer maxJobsPerDay,
            @NotNull(message = "Choose one of the options.") AcceptMode acceptMode,
            @NotNull(message = "Choose one of the options.") Integer rescheduleFreeMin,
            @Nullable Long lateCancelFeeCents,
            @Nullable Integer lateCancelFeeBps,
            @Nullable Long emergencyPremiumCents,
            @Nullable Integer emergencyPremiumBps,
            @Nullable Long holidayPremiumCents,

            @Size(max = 20, message = "Choose one of the options.") @Nullable
            List<String> serviceAreas) {}

    record RulesResponse(
            int intervalMin,
            int bufferMin,
            int minNoticeMin,
            @Nullable Integer sameDayCutoffMin,
            int horizonDays,
            int maxJobsPerDay,
            AcceptMode acceptMode,
            int rescheduleFreeMin,
            @Nullable Long lateCancelFeeCents,
            @Nullable Integer lateCancelFeeBps,
            @Nullable Long emergencyPremiumCents,
            @Nullable Integer emergencyPremiumBps,
            long holidayPremiumCents,
            List<String> serviceAreas,
            List<String> zones,
            @Nullable Instant lastSavedAt) {}

    // ── time off ──
    record TimeOffBody(
            @Nullable String memberUserId,
            @Nullable LocalDate startsOn,
            @Nullable LocalDate endsOn,

            @NotNull(message = "Choose closed or special hours.")
            TimeOff.Kind kind,

            @Nullable List<List<String>> specialRanges,
            @Nullable String reason) {}

    record TimeOffResponse(
            String id,
            @Nullable String memberUserId,
            @Nullable String memberName,
            LocalDate startsOn,
            LocalDate endsOn,
            TimeOff.Kind kind,
            List<List<String>> specialRanges,
            @Nullable String reason) {}

    record HolidayResponse(String key, LocalDate date, boolean open) {}

    record TimeOffListResponse(
            List<TimeOffResponse> entries, List<HolidayResponse> holidays, long holidayPremiumCents) {}

    record ConflictsResponse(int bookings) {}

    record OpenBody(
            @NotNull(message = "Choose open or closed.") Boolean open) {}

    // ── sync & team ──
    /** {@code authorizationUrl}: after "Connect" for Google / Outlook — the Studio sends the browser there. */
    record CalendarResponse(
            CalendarProvider provider,
            boolean connected,
            @Nullable String accountLabel,
            @Nullable Instant lastSyncAt,
            @Nullable String feedUrl,
            @Nullable CalendarLinkState state,
            boolean available,
            List<String> sources,
            @Nullable URI authorizationUrl) {}

    record SourceResponse(String id, String name, boolean primary, boolean selected) {}

    /** {@code authorizationUrl}: the provider must first allow Northline to list calendars (incremental consent). */
    record SourcesResponse(
            List<SourceResponse> items, @Nullable URI authorizationUrl) {}

    record ChooseSourcesBody(
            @NotEmpty(message = ChooseCalendarSources.NONE)
            List<@NotNull(message = ChooseCalendarSources.NONE) String> calendarIds) {}

    record TeamMemberResponse(
            String userId,
            String name,
            MerchantRole role,
            boolean bookable,
            @Nullable Map<String, List<List<String>>> days) {}

    record SyncResponse(List<CalendarResponse> calendars, List<TeamMemberResponse> team) {}

    record BookableBody(
            @NotNull(message = "Choose bookable or hidden.") Boolean bookable) {}
}
