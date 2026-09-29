package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.booking.application.QuoteUseCases.DeclineQuoteRequest;
import ca.northline.booking.application.QuoteUseCases.ListQuoteRequests;
import ca.northline.booking.application.QuoteUseCases.ReviseQuote;
import ca.northline.booking.application.QuoteUseCases.SendQuote;
import ca.northline.booking.application.QuoteUseCases.ViewQuote;
import ca.northline.booking.web.QuoteResponses.QuoteRequestResponse;
import ca.northline.booking.web.QuoteResponses.QuoteResponse;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Appointments › Quote requests and the itemized quote composer. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class QuoteController {

    private final ListQuoteRequests listQuoteRequests;
    private final SendQuote sendQuote;
    private final ReviseQuote reviseQuote;
    private final DeclineQuoteRequest declineQuoteRequest;
    private final ViewQuote viewQuote;
    private final QuoteWebMapper mapper;

    @GetMapping("/quote-requests")
    @RequiresMerchant(VIEW)
    ListResponse<QuoteRequestResponse> requests(@PathVariable String merchantId) {
        return new ListResponse<>(mapper.toResponses(listQuoteRequests.list(merchantId)));
    }

    /** "Send quote" — version 1 of this merchant's reply. 409 {@code quote_already_sent} once sent (revise instead). */
    @PostMapping("/quote-requests/{requestId}/quotes")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    QuoteResponse send(
            @PathVariable String merchantId,
            @PathVariable String requestId,
            @Valid @RequestBody QuoteBody body,
            CurrentMember member) {
        return mapper.toResponse(
                sendQuote.send(new SendQuote.Command(merchantId, requestId, member.userId(), mapper.toContent(body))));
    }

    @PostMapping("/quote-requests/{requestId}/decline")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@PathVariable String merchantId, @PathVariable String requestId, CurrentMember member) {
        declineQuoteRequest.decline(merchantId, requestId, member.userId());
    }

    /** "View as customer". */
    @GetMapping("/quotes/{quoteId}")
    @RequiresMerchant(VIEW)
    QuoteResponse quote(@PathVariable String merchantId, @PathVariable String quoteId) {
        return mapper.toResponse(viewQuote.view(merchantId, quoteId));
    }

    /** "Revise" — creates version + 1; the quote addressed here becomes {@code superseded}. */
    @PostMapping("/quotes/{quoteId}/revisions")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    QuoteResponse revise(
            @PathVariable String merchantId,
            @PathVariable String quoteId,
            @Valid @RequestBody QuoteBody body,
            CurrentMember member) {
        return mapper.toResponse(reviseQuote.revise(
                new ReviseQuote.Command(merchantId, quoteId, member.userId(), mapper.toContent(body))));
    }
}
