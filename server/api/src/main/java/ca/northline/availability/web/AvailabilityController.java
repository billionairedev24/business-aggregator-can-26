package ca.northline.availability.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.availability.application.AvailabilityUseCases.AddTimeOff;
import ca.northline.availability.application.AvailabilityUseCases.ConnectCalendar;
import ca.northline.availability.application.AvailabilityUseCases.CountConflicts;
import ca.northline.availability.application.AvailabilityUseCases.DisconnectCalendar;
import ca.northline.availability.application.AvailabilityUseCases.ListPreviewServices;
import ca.northline.availability.application.AvailabilityUseCases.PreviewSlots;
import ca.northline.availability.application.AvailabilityUseCases.RemoveTimeOff;
import ca.northline.availability.application.AvailabilityUseCases.SaveHours;
import ca.northline.availability.application.AvailabilityUseCases.SaveRules;
import ca.northline.availability.application.AvailabilityUseCases.SetBookable;
import ca.northline.availability.application.AvailabilityUseCases.SetHolidayOpen;
import ca.northline.availability.application.AvailabilityUseCases.ViewHours;
import ca.northline.availability.application.AvailabilityUseCases.ViewRules;
import ca.northline.availability.application.AvailabilityUseCases.ViewSync;
import ca.northline.availability.application.AvailabilityUseCases.ViewTimeOff;
import ca.northline.availability.application.CalendarUseCases.ChooseCalendarSources;
import ca.northline.availability.application.CalendarUseCases.ListCalendarSources;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.web.AvailabilityDtos.BookableBody;
import ca.northline.availability.web.AvailabilityDtos.CalendarResponse;
import ca.northline.availability.web.AvailabilityDtos.ChooseSourcesBody;
import ca.northline.availability.web.AvailabilityDtos.ConflictsResponse;
import ca.northline.availability.web.AvailabilityDtos.HolidayResponse;
import ca.northline.availability.web.AvailabilityDtos.HoursResponse;
import ca.northline.availability.web.AvailabilityDtos.OpenBody;
import ca.northline.availability.web.AvailabilityDtos.PreviewBody;
import ca.northline.availability.web.AvailabilityDtos.PreviewResponse;
import ca.northline.availability.web.AvailabilityDtos.RulesBody;
import ca.northline.availability.web.AvailabilityDtos.RulesResponse;
import ca.northline.availability.web.AvailabilityDtos.SaveHoursBody;
import ca.northline.availability.web.AvailabilityDtos.ServiceResponse;
import ca.northline.availability.web.AvailabilityDtos.SourcesResponse;
import ca.northline.availability.web.AvailabilityDtos.SyncResponse;
import ca.northline.availability.web.AvailabilityDtos.TeamMemberResponse;
import ca.northline.availability.web.AvailabilityDtos.TimeOffBody;
import ca.northline.availability.web.AvailabilityDtos.TimeOffListResponse;
import ca.northline.availability.web.AvailabilityDtos.TimeOffResponse;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Availability: weekly hours, booking rules, time off &amp; holidays, calendar sync &amp; team. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/availability")
@RequiredArgsConstructor
class AvailabilityController {

    private final ViewHours viewHours;
    private final SaveHours saveHours;
    private final PreviewSlots previewSlots;
    private final ListPreviewServices previewServices;
    private final ViewRules viewRules;
    private final SaveRules saveRules;
    private final ViewTimeOff viewTimeOff;
    private final AddTimeOff addTimeOff;
    private final RemoveTimeOff removeTimeOff;
    private final CountConflicts countConflicts;
    private final SetHolidayOpen setHolidayOpen;
    private final ViewSync viewSync;
    private final ConnectCalendar connectCalendar;
    private final DisconnectCalendar disconnectCalendar;
    private final SetBookable setBookable;
    private final ListCalendarSources listSources;
    private final ChooseCalendarSources chooseSources;
    private final AvailabilityWebMapper mapper;

    @GetMapping("/hours")
    @RequiresMerchant(VIEW)
    HoursResponse hours(@PathVariable String merchantId) {
        return mapper.toResponse(viewHours.view(merchantId));
    }

    @PutMapping("/hours")
    @RequiresMerchant(EDIT)
    HoursResponse saveHours(
            @PathVariable String merchantId, @Valid @RequestBody SaveHoursBody body, CurrentMember member) {
        var members = IntStream.range(0, body.members().size())
                .mapToObj(i -> {
                    var m = body.members().get(i);
                    return new SaveHours.MemberDays(
                            m.memberUserId(), mapper.toHours(m.days(), "members[%d].days".formatted(i)));
                })
                .toList();
        return mapper.toResponse(
                saveHours.save(new SaveHours.Command(merchantId, member.userId(), body.effectiveFrom(), members)));
    }

    /** Live preview (hours − jobs − buffer at the interval); unsaved hours/rules may be sent along. */
    @PostMapping("/preview")
    @RequiresMerchant(VIEW)
    PreviewResponse preview(@PathVariable String merchantId, @Valid @RequestBody PreviewBody body) {
        var ranges = body.ranges() == null ? null : mapper.toRanges(body.ranges(), "ranges");
        return mapper.toResponse(previewSlots.preview(new PreviewSlots.Query(
                merchantId,
                body.memberUserId(),
                body.date(),
                body.durationMin(),
                ranges,
                body.intervalMin(),
                body.bufferMin())));
    }

    @GetMapping("/services")
    @RequiresMerchant(VIEW)
    ListResponse<ServiceResponse> services(@PathVariable String merchantId, Locale locale) {
        return new ListResponse<>(mapper.toServiceResponses(previewServices.list(merchantId, locale.getLanguage())));
    }

    @GetMapping("/rules")
    @RequiresMerchant(VIEW)
    RulesResponse rules(@PathVariable String merchantId) {
        return mapper.toResponse(viewRules.rules(merchantId));
    }

    @PutMapping("/rules")
    @RequiresMerchant(EDIT)
    RulesResponse saveRules(@PathVariable String merchantId, @Valid @RequestBody RulesBody body, CurrentMember member) {
        return mapper.toResponse(saveRules.saveRules(merchantId, member.userId(), mapper.toRules(body)));
    }

    @GetMapping("/time-off")
    @RequiresMerchant(VIEW)
    TimeOffListResponse timeOff(@PathVariable String merchantId) {
        return mapper.toResponse(viewTimeOff.view(merchantId));
    }

    @PostMapping("/time-off")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    TimeOffResponse addTimeOff(
            @PathVariable String merchantId, @Valid @RequestBody TimeOffBody body, CurrentMember member) {
        return mapper.toResponse(addTimeOff.add(new AddTimeOff.Command(
                merchantId,
                member.userId(),
                body.memberUserId(),
                body.startsOn(),
                body.endsOn(),
                body.kind(),
                mapper.toRanges(body.specialRanges(), "specialRanges"),
                body.reason())));
    }

    @DeleteMapping("/time-off/{timeOffId}")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeTimeOff(@PathVariable String merchantId, @PathVariable String timeOffId, CurrentMember member) {
        removeTimeOff.remove(merchantId, member.userId(), timeOffId);
    }

    /** "2 existing bookings fall in this range". */
    @GetMapping("/time-off/conflicts")
    @RequiresMerchant(VIEW)
    ConflictsResponse conflicts(
            @PathVariable String merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String memberUserId) {
        return new ConflictsResponse(countConflicts.count(merchantId, memberUserId, from, to == null ? from : to));
    }

    @PutMapping("/holidays/{date}")
    @RequiresMerchant(EDIT)
    HolidayResponse holiday(
            @PathVariable String merchantId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody OpenBody body,
            CurrentMember member) {
        return mapper.toResponse(setHolidayOpen.set(merchantId, member.userId(), date, body.open()));
    }

    @GetMapping("/sync")
    @RequiresMerchant(VIEW)
    SyncResponse sync(@PathVariable String merchantId, CurrentMember member) {
        return mapper.toResponse(viewSync.view(merchantId, member.userId()));
    }

    /**
     * Connect the caller's calendar ({@code google}, {@code outlook}, {@code ical}). Google and Outlook answer with an
     * {@code authorizationUrl} (OAuth consent); the provider redirects back to {@code /api/v1/calendar/oauth/…}.
     */
    @PostMapping("/calendars/{provider}")
    @RequiresMerchant(EDIT)
    CalendarResponse connect(@PathVariable String merchantId, @PathVariable String provider, CurrentMember member) {
        return mapper.toResponse(connectCalendar.connect(merchantId, member.userId(), provider(provider)));
    }

    @DeleteMapping("/calendars/{provider}")
    @RequiresMerchant(EDIT)
    CalendarResponse disconnect(@PathVariable String merchantId, @PathVariable String provider, CurrentMember member) {
        return mapper.toResponse(disconnectCalendar.disconnect(merchantId, member.userId(), provider(provider)));
    }

    /** "Choose calendars": the caller's calendars at the provider, the ones that block slots marked. */
    @GetMapping("/calendars/{provider}/sources")
    @RequiresMerchant(EDIT)
    SourcesResponse sources(@PathVariable String merchantId, @PathVariable String provider, CurrentMember member) {
        return mapper.toResponse(listSources.list(merchantId, member.userId(), provider(provider)));
    }

    @PutMapping("/calendars/{provider}/sources")
    @RequiresMerchant(EDIT)
    SourcesResponse chooseSources(
            @PathVariable String merchantId,
            @PathVariable String provider,
            @Valid @RequestBody ChooseSourcesBody body,
            CurrentMember member) {
        return mapper.toResponse(
                chooseSources.choose(merchantId, member.userId(), provider(provider), body.calendarIds()));
    }

    /** Show or hide a member on the booking calendar (owner only). */
    @PutMapping("/team/{userId}")
    @RequiresMerchant(MANAGE)
    TeamMemberResponse bookable(
            @PathVariable String merchantId,
            @PathVariable String userId,
            @Valid @RequestBody BookableBody body,
            CurrentMember member) {
        return mapper.toResponse(setBookable.set(merchantId, member.userId(), userId, body.bookable()));
    }

    private static CalendarProvider provider(String code) {
        try {
            return CodedEnum.fromCode(CalendarProvider.class, code);
        } catch (IllegalArgumentException _) {
            throw new NotFound("calendar provider", code);
        }
    }
}
