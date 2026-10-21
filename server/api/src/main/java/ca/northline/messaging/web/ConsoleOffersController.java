package ca.northline.messaging.web;

import ca.northline.messaging.application.OfferCampaigns;
import ca.northline.messaging.domain.OfferDraft;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — offers (S-157): Northline's own offer by email or text to the customers of a market who agreed to
 * that channel (CASL express consent), throttled through the worker, which adds the sender identification and the
 * unsubscribe link and checks each person's consent again before sending. Screen {@code offers} (admins, finance);
 * sending and testing need the {@code offers} action; both are audit-logged ({@code offer.sent}, {@code
 * offer.test_sent}).
 *
 * <pre>
 * GET  /api/v1/console/offers            {markets, perMinute, campaigns: [campaign]}
 * POST /api/v1/console/offers/preview    {draft} → {en, fr: {subject, html, text, sms, smsSegments}, audience}
 * POST /api/v1/console/offers/test       {draft} → 204 (to the staff member's own email or mobile, "[Test]")
 * POST /api/v1/console/offers            {draft} → 201 campaign; 409 no_recipients
 * </pre>
 *
 * {@code draft}: {@code market} (a served province code), {@code channel} ({@code email | sms}), {@code titleEn},
 * {@code bodyEn}, {@code titleFr}, {@code bodyFr}, {@code path} (a page of the consumer site, {@code /shop}).
 */
@RestController
@RequestMapping("/api/v1/console/offers")
@RequiredArgsConstructor
class ConsoleOffersController {

    private final OfferCampaigns offers;

    record DraftRequest(
            @NotBlank(message = OfferDraft.MARKET) String market,
            @NotBlank(message = OfferDraft.CHANNEL) String channel,
            @NotBlank(message = OfferDraft.TITLE_EN) String titleEn,
            @NotBlank(message = OfferDraft.BODY_EN) String bodyEn,
            @NotBlank(message = OfferDraft.TITLE_FR) String titleFr,
            @NotBlank(message = OfferDraft.BODY_FR) String bodyFr,
            @NotBlank(message = OfferDraft.PATH) String path) {

        OfferDraft draft() {
            return new OfferDraft(market, channel, titleEn, bodyEn, titleFr, bodyFr, path);
        }
    }

    @Operation(summary = "Offers sent, how far each got, and the markets one can be sent to")
    @GetMapping
    @RequiresConsole(ConsoleScreen.OFFERS)
    OfferCampaigns.Overview list() {
        return offers.overview();
    }

    @Operation(summary = "An offer as customers will get it (email and text, en and fr) and how many would")
    @PostMapping("/preview")
    @RequiresConsole(ConsoleScreen.OFFERS)
    OfferCampaigns.Preview preview(@Valid @RequestBody DraftRequest body) {
        return offers.preview(body.draft());
    }

    @Operation(summary = "Send the offer to yourself only, marked as a test")
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresConsole(value = ConsoleScreen.OFFERS, actions = ConsoleAction.OFFERS)
    void test(@Valid @RequestBody DraftRequest body, CurrentStaff staff, Locale locale) {
        offers.test(body.draft(), staff.userId(), staff.roleCodes(), "fr".equals(locale.getLanguage()) ? "fr" : "en");
    }

    @Operation(summary = "Send the offer to every consenting customer of the market (throttled)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.OFFERS, actions = ConsoleAction.OFFERS)
    OfferCampaigns.Campaign send(@Valid @RequestBody DraftRequest body, CurrentStaff staff) {
        return offers.send(body.draft(), staff.userId(), staff.roleCodes());
    }
}
