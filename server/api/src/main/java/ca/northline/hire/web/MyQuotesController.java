package ca.northline.hire.web;

import ca.northline.hire.application.QuoteFlow.CompareQuotes;
import ca.northline.hire.application.QuoteFlow.Comparison;
import ca.northline.hire.application.QuoteFlow.ConfirmAcceptance;
import ca.northline.hire.application.QuoteFlow.DeclineQuote;
import ca.northline.hire.application.QuoteFlow.QuotePage;
import ca.northline.hire.application.QuoteFlow.RequestQuotes;
import ca.northline.hire.application.QuoteFlow.Requested;
import ca.northline.hire.application.QuoteFlow.StartAcceptance;
import ca.northline.hire.application.QuoteFlow.ViewQuote;
import ca.northline.hire.domain.QuoteAsk;
import ca.northline.hire.domain.QuoteVisit;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.security.CurrentUser;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quotes on the consumer side (S-56). Everything is the signed-in customer's own: someone else's request or quote is
 * 404. Accepting is money-moving: {@code Idempotency-Key} (CLAUDE.md) and the S-51 step-up rule ({@code X-Step-Up}).
 */
@RestController
@RequiredArgsConstructor
class MyQuotesController {

    private final RequestQuotes requests;
    private final CompareQuotes comparisons;
    private final ViewQuote views;
    private final DeclineQuote declines;
    private final StartAcceptance acceptances;
    private final ConfirmAcceptance confirmations;
    private final IdempotentRequests idempotent;

    /** "Send request to N providers". */
    @PostMapping("/api/v1/me/quote-requests")
    @ResponseStatus(HttpStatus.CREATED)
    Requested request(@RequestBody QuoteAsk body, CurrentUser user) {
        return requests.request(user.userId(), body);
    }

    @GetMapping("/api/v1/me/quote-requests/{requestId}")
    Comparison compare(
            @PathVariable String requestId, @RequestParam(defaultValue = "en") String lang, CurrentUser user) {
        return comparisons.compare(user.userId(), requestId, Languages.of(lang));
    }

    /** One quote with every line; the provider sees it was read. */
    @GetMapping("/api/v1/me/quotes/{quoteId}")
    QuotePage quote(@PathVariable String quoteId, @RequestParam(defaultValue = "en") String lang, CurrentUser user) {
        return views.quote(user.userId(), quoteId, Languages.of(lang));
    }

    @PostMapping("/api/v1/me/quotes/{quoteId}/decline")
    ResponseEntity<Void> decline(@PathVariable String quoteId, CurrentUser user) {
        declines.decline(user.userId(), quoteId);
        return ResponseEntity.noContent().build();
    }

    /** "Accept · hold $…": opens the escrow payment of the deposit. */
    @PostMapping("/api/v1/me/quotes/{quoteId}/accept")
    ResponseEntity<String> accept(
            @PathVariable String quoteId,
            @RequestBody(required = false) @Nullable QuoteVisit body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestHeader(value = "X-Step-Up", required = false) @Nullable String stepUp,
            CurrentUser user) {
        var visit = visit(body);
        var answer = idempotent.run(
                "consumer:%s:quote-accept:%s".formatted(user.userId(), quoteId),
                key,
                visit,
                HttpStatus.OK.value(),
                () -> acceptances.start(user.userId(), quoteId, visit, key, user.mfa(), stepUp));
        return MyBookingsController.json(answer);
    }

    /** The card is authorized: hold the deposit in escrow, accept the quote and book it. */
    @PostMapping("/api/v1/me/quotes/{quoteId}/accept/confirm")
    ResponseEntity<String> confirm(
            @PathVariable String quoteId,
            @RequestBody(required = false) @Nullable QuoteVisit body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        var visit = visit(body);
        var answer = idempotent.run(
                "consumer:%s:quote-confirm:%s".formatted(user.userId(), quoteId),
                key,
                visit,
                HttpStatus.CREATED.value(),
                () -> confirmations.confirm(user.userId(), quoteId, visit));
        return MyBookingsController.json(answer);
    }

    private static QuoteVisit visit(@Nullable QuoteVisit body) {
        return Objects.requireNonNullElseGet(body, () -> new QuoteVisit(null, null, null, null));
    }
}
