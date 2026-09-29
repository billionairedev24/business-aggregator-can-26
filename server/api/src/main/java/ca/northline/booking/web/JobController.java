package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.booking.application.AdvanceJob;
import ca.northline.booking.application.AdvanceJob.Step;
import ca.northline.booking.application.ListJobs;
import ca.northline.booking.application.RequestApproval;
import ca.northline.booking.application.UploadMedia;
import ca.northline.booking.application.ViewJob;
import ca.northline.booking.domain.GeoPoint;
import ca.northline.booking.web.JobBodies.ApprovalBody;
import ca.northline.booking.web.JobBodies.CompleteBody;
import ca.northline.booking.web.JobBodies.PositionBody;
import ca.northline.booking.web.JobResponses.ApprovalResponse;
import ca.northline.booking.web.JobResponses.JobDetailResponse;
import ca.northline.booking.web.JobResponses.JobResponse;
import ca.northline.booking.web.JobResponses.MediaResponse;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Appointments: the job calendar, the job card and today's job flow. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class JobController {

    static final String RANGE_TOO_LONG = "Pick at most 62 days.";

    private final ListJobs listJobs;
    private final ViewJob viewJob;
    private final AdvanceJob advanceJob;
    private final RequestApproval requestApproval;
    private final UploadMedia uploadMedia;
    private final JobWebMapper mapper;

    /** Jobs starting in [from, to) — the week calendar, day and list views. */
    @GetMapping("/jobs")
    @RequiresMerchant(VIEW)
    ListResponse<JobResponse> jobs(
            @PathVariable String merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            CurrentMember member) {
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > 62) {
            throw RuleViolation.of("to", "range", RANGE_TOO_LONG);
        }
        return new ListResponse<>(mapper.toResponses(listJobs.list(new ListJobs.Query(member, from, to))));
    }

    @GetMapping("/jobs/{jobId}")
    @RequiresMerchant(VIEW)
    JobDetailResponse job(@PathVariable String merchantId, @PathVariable String jobId, CurrentMember member) {
        return mapper.toResponse(viewJob.view(member, jobId));
    }

    /** Start travel: confirmed → en route. */
    @PostMapping("/jobs/{jobId}/en-route")
    @RequiresMerchant(OPERATE)
    JobDetailResponse startTravel(
            @PathVariable String merchantId,
            @PathVariable String jobId,
            @Valid @RequestBody(required = false) @Nullable PositionBody body,
            CurrentMember member) {
        return advance(member, jobId, Step.START_TRAVEL, body == null ? null : body.point(), List.of(), null);
    }

    /** Check in on site: en route → on site (timestamped, geofenced). */
    @PostMapping("/jobs/{jobId}/on-site")
    @RequiresMerchant(OPERATE)
    JobDetailResponse checkIn(
            @PathVariable String merchantId,
            @PathVariable String jobId,
            @Valid @RequestBody(required = false) @Nullable PositionBody body,
            CurrentMember member) {
        return advance(member, jobId, Step.CHECK_IN, body == null ? null : body.point(), List.of(), null);
    }

    /** Complete with photos + report: on site → completed. */
    @PostMapping("/jobs/{jobId}/complete")
    @RequiresMerchant(OPERATE)
    JobDetailResponse complete(
            @PathVariable String merchantId,
            @PathVariable String jobId,
            @Valid @RequestBody CompleteBody body,
            CurrentMember member) {
        return advance(
                member,
                jobId,
                Step.COMPLETE,
                body.point(),
                Objects.requireNonNullElse(body.photoMediaIds(), List.of()),
                body.report());
    }

    /** Request extra parts approval (customer approves in-app). */
    @PostMapping("/jobs/{jobId}/approvals")
    @RequiresMerchant(OPERATE)
    @ResponseStatus(HttpStatus.CREATED)
    ApprovalResponse requestApproval(
            @PathVariable String merchantId,
            @PathVariable String jobId,
            @Valid @RequestBody ApprovalBody body,
            CurrentMember member) {
        return mapper.toResponse(requestApproval.request(new RequestApproval.Command(
                member,
                jobId,
                Objects.requireNonNullElse(body.description(), ""),
                Objects.requireNonNullElse(body.amountCents(), 0L))));
    }

    /** Upload a completion photo or quote attachment (multipart field {@code file}). */
    @PostMapping(path = "/jobs/media", consumes = "multipart/form-data")
    @RequiresMerchant(OPERATE)
    @ResponseStatus(HttpStatus.CREATED)
    MediaResponse upload(@PathVariable String merchantId, @RequestPart("file") MultipartFile file, CurrentMember member)
            throws IOException {
        return mapper.toResponse(uploadMedia.upload(new UploadMedia.Command(
                merchantId,
                member.userId(),
                Objects.requireNonNullElse(file.getOriginalFilename(), "attachment"),
                Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                file.getBytes())));
    }

    private JobDetailResponse advance(
            CurrentMember member,
            String jobId,
            Step step,
            @Nullable GeoPoint point,
            List<String> photos,
            @Nullable String report) {
        return mapper.toResponse(
                advanceJob.advance(new AdvanceJob.Command(member, jobId, step, point, photos, report)));
    }
}
