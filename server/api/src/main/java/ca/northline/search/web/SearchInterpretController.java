package ca.northline.search.web;

import ca.northline.search.application.InterpretSearch;
import ca.northline.search.application.SearchRateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-132: {@code POST /api/v1/search/interpret} — natural-language search to the search API's filters. Public like search
 * (guests browse), so the AI budget is per person: the signed-in user, else the BFF's guest id, else a hash of the
 * client address. Also counted by search's own per-address rate limit.
 */
@Tag(name = "Search")
@RestController
@RequiredArgsConstructor
class SearchInterpretController {

    static final String GUEST = "X-Northline-Guest";

    private final InterpretSearch interpret;
    private final SearchRateLimit rateLimit;

    record Body(
            @Nullable String text,
            @Nullable Boolean hasLocation,
            @Nullable String lang) {}

    @Operation(
            summary = "Natural-language search → filters",
            description = "Turns what the person typed into GET /api/v1/search parameters (AI-assisted; the person "
                    + "sees and can remove each filter). 503 ai_unavailable when no model is configured.")
    @PostMapping("/api/v1/search/interpret")
    InterpretSearch.Interpretation interpret(
            @RequestBody Body body,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String language,
            @RequestHeader(name = GUEST, required = false) @Nullable String guest,
            @Nullable Principal principal,
            HttpServletRequest request) {
        var address = ClientAddress.of(request);
        if (!rateLimit.allow(address)) {
            throw new SearchController.TooManySearches();
        }
        var lang = body.lang() != null
                ? body.lang()
                : language != null && language.toLowerCase(Locale.ROOT).startsWith("fr") ? "fr" : "en";
        return interpret.interpret(
                visitor(principal, guest, address),
                body.text() == null ? "" : body.text(),
                "fr".equals(lang) ? "fr" : "en",
                Boolean.TRUE.equals(body.hasLocation()));
    }

    /** Search's own per-address limit, in the same shape as {@code GET /api/v1/search}. */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> tooMany(SearchController.TooManySearches ignored) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many searches. Try again in a minute.");
        problem.setType(URI.create("https://northline.ca/problems/rate-limited"));
        problem.setProperty("code", "rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(problem);
    }

    static String visitor(@Nullable Principal principal, @Nullable String guest, String address) {
        if (principal != null) {
            return principal.getName();
        }
        return "visitor:" + sha256(guest != null && !guest.isBlank() ? "guest:" + guest : "addr:" + address);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
