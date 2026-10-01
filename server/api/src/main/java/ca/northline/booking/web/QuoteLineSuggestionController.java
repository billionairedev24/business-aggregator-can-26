package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;

import ca.northline.booking.application.DraftQuoteLines;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-131: {@code POST /api/v1/merchants/{merchantId}/quote-requests/{requestId}/line-suggestions} — suggested lines for
 * the quote composer (no prices). Nothing is saved or sent.
 */
@RestController
@RequiredArgsConstructor
class QuoteLineSuggestionController {

    private final DraftQuoteLines drafts;

    record Body(
            @Nullable @Size(max = 1000, message = "Keep notes under 1,000 characters.")
            String notes) {}

    @PostMapping("/api/v1/merchants/{merchantId}/quote-requests/{requestId}/line-suggestions")
    @RequiresMerchant(EDIT)
    DraftQuoteLines.Suggestion suggest(
            @PathVariable String merchantId,
            @PathVariable String requestId,
            @Valid @RequestBody(required = false) @Nullable Body body,
            CurrentMember member,
            Locale locale) {
        return drafts.suggest(merchantId, requestId, member.userId(), body == null ? null : body.notes(), locale);
    }
}
