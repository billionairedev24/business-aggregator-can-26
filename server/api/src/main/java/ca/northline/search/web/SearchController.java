package ca.northline.search.web;

import ca.northline.search.application.SearchListings;
import ca.northline.search.application.SearchRateLimit;
import ca.northline.search.application.SearchSettings;
import ca.northline.search.application.SuggestListings;
import ca.northline.search.web.SearchDtos.SearchResponse;
import ca.northline.search.web.SearchDtos.SuggestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public search API (S-44): anonymous callers welcome ({@code /api/v1/search/**} is open in SecurityConfig), rate
 * limited per client address ({@link ClientAddress}). Contract for the consumer web and app: docs/runbooks/search.md
 * § 8.
 */
@Tag(name = "Search", description = "Listings, dishes and businesses of one market, in English or French")
@RestController
@RequiredArgsConstructor
class SearchController {

    private final SearchListings search;
    private final SuggestListings suggest;
    private final SearchRateLimit rateLimit;
    private final SearchSettings settings;
    private final SearchWebMapper mapper;

    @Operation(
            summary = "Search",
            description = "Only live, approved listings of active businesses in the market. Relevance weighs the text "
                    + "match, trust tier, rating and, with lat/lng, nearness. Pages with `after` = the previous "
                    + "page's `next`; facets on the first page.")
    @ApiResponse(responseCode = "200", description = "A page of results")
    @ApiResponse(responseCode = "422", description = "A parameter breaks a rule: {errors: [{field, rule, message}]}")
    @ApiResponse(responseCode = "429", description = "Too many requests from this address (Retry-After)")
    @GetMapping("/api/v1/search")
    SearchResponse search(
            @ParameterObject SearchParams params,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String language,
            HttpServletRequest request) {
        limit(request);
        return mapper.toResponse(search.search(params.toQuery(settings.defaultMarket(), language)));
    }

    @Operation(
            summary = "Suggestions as you type",
            description = "Listing, dish and business names and categories that start a word with `q`, heaviest "
                    + "(trust, rating, sales) first, each with the typed part to highlight.")
    @ApiResponse(responseCode = "200", description = "{items: [...]}")
    @ApiResponse(responseCode = "422", description = "q is empty or too long, or a parameter breaks a rule")
    @ApiResponse(responseCode = "429", description = "Too many requests from this address (Retry-After)")
    @GetMapping("/api/v1/search/suggest")
    SuggestResponse suggest(
            @ParameterObject SearchParams params,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String language,
            HttpServletRequest request) {
        limit(request);
        return mapper.toSuggestResponse(suggest.suggest(params.toSuggest(settings.defaultMarket(), language)));
    }

    private void limit(HttpServletRequest request) {
        if (!rateLimit.allow(ClientAddress.of(request))) {
            throw new TooManySearches();
        }
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> tooMany(TooManySearches ignored) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many searches. Try again in a minute.");
        problem.setType(URI.create("https://northline.ca/problems/rate-limited"));
        problem.setProperty("code", "rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(problem);
    }

    /** Over the per-address limit. */
    static final class TooManySearches extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooManySearches() {
            super("rate limited", null, false, false);
        }
    }
}
