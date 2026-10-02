package ca.northline.messaging.web;

import ca.northline.messaging.application.Consents.ConsentDesk;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentRecord;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — CASL proof of consent (S-108), on the privacy screen (admin, privacy officer, support lead): every
 * grant and withdrawal of a person, found by their account id or by an email address or phone number they consented
 * with (its hash — it works after the account is erased), and a withdrawal staff record for a person who asked by
 * phone, mail or email (action {@code privacy}, audit-logged).
 *
 * <pre>
 * GET  /api/v1/console/consents?userId=…|contact=…        {items: [record]}, newest first
 * POST /api/v1/console/consents/withdrawals  {userId, category}   → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/consents")
@RequiredArgsConstructor
class ConsentDeskController {

    static final String CONTACT_REQUIRED = "Enter an account id, an email address or a phone number.";

    private final ConsentDesk desk;

    record RecordView(
            String id,
            String userId,
            String category,
            String action,
            Instant at,
            String source,
            @Nullable String wordingVersion,
            @Nullable String language,
            @Nullable String ipPrefix,
            boolean addressKnown,
            @Nullable String actorId) {

        static RecordView of(ConsentRecord r) {
            return new RecordView(
                    r.id(),
                    r.userId(),
                    r.category().code(),
                    r.granted() ? "granted" : "withdrawn",
                    r.at(),
                    r.source().code(),
                    r.wordingVersion(),
                    r.language(),
                    r.ipPrefix(),
                    r.addressHash() != null,
                    r.actorId());
        }
    }

    record WithdrawalRequest(
            @NotBlank(message = CONTACT_REQUIRED) String userId,

            @NotBlank(message = "Choose from the list.")
            @Pattern(regexp = "marketing_email|marketing_sms|marketing_push", message = "Choose from the list.")
            String category) {}

    @Operation(summary = "A person's consent records (proof of consent), by account id or contact")
    @GetMapping
    @RequiresConsole(ConsoleScreen.PRIVACY)
    ListResponse<RecordView> lookup(
            @RequestParam(required = false) @Nullable String userId,
            @RequestParam(required = false) @Nullable String contact) {
        if ((userId == null || userId.isBlank()) && (contact == null || contact.isBlank())) {
            throw RuleViolation.of("contact", "required", CONTACT_REQUIRED);
        }
        return new ListResponse<>(
                desk.lookup(userId, contact).stream().map(RecordView::of).toList());
    }

    @Operation(summary = "Withdraw a person's consent on their behalf (asked by phone, mail or email)")
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresConsole(value = ConsoleScreen.PRIVACY, actions = ConsoleAction.PRIVACY)
    void withdraw(@Valid @RequestBody WithdrawalRequest body, CurrentStaff staff) {
        desk.withdraw(
                body.userId().strip(),
                CodedEnum.fromCode(ConsentCategory.class, body.category()),
                staff.userId(),
                staff.roleCodes());
    }
}
