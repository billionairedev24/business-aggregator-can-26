package ca.northline.merchants.web;

import ca.northline.merchants.application.RestrictedLicenceUseCases.Decision;
import ca.northline.merchants.application.RestrictedLicenceUseCases.LicenceQueue;
import ca.northline.merchants.application.RestrictedLicenceUseCases.QueueItem;
import ca.northline.merchants.domain.LicenceRules;
import ca.northline.shared.ListResponse;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — licences for age-restricted sales in the vetting queue (admin and trust &amp; safety open it,
 * deciding needs {@code vet}; every decision is audited and the owners are emailed).
 *
 * <pre>
 * GET  /api/v1/console/vetting/licences[?status=pending|approved|rejected|expired|replaced][&amp;province=][&amp;market=]   {items}
 * GET  /api/v1/console/vetting/licences/{id}/document                    the uploaded file (inline)
 * POST /api/v1/console/vetting/licences/{id}/decision {decision: approve|reject, reason?, note?}   QueueItem
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/vetting/licences")
@RequiredArgsConstructor
class RestrictedLicenceQueueController {

    private final PlaceFilter places;
    private final LicenceQueue queue;

    record DecisionRequest(
            @NotBlank(message = LicenceRules.DECISION)
            @Pattern(regexp = "approve|reject", message = LicenceRules.DECISION)
            String decision,

            @Nullable String reason,

            @Nullable @Size(max = 500, message = LicenceRules.NOTE)
            String note) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.VETTING)
    ListResponse<QueueItem> list(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return new ListResponse<>(queue.queue(places.resolve(province, market).scope(), status));
    }

    @GetMapping("/{licenceId}/document")
    @RequiresConsole(ConsoleScreen.VETTING)
    ResponseEntity<byte[]> document(@PathVariable String licenceId) {
        var content = queue.document(licenceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.document().contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(content.document().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes().toArray());
    }

    @PostMapping("/{licenceId}/decision")
    @RequiresConsole(value = ConsoleScreen.VETTING, actions = ConsoleAction.VET)
    QueueItem decide(@PathVariable String licenceId, @Valid @RequestBody DecisionRequest body, CurrentStaff staff) {
        return queue.decide(new Decision(
                licenceId,
                "approve".equals(body.decision()),
                body.reason(),
                body.note(),
                staff.userId(),
                staff.roleCodes()));
    }
}
