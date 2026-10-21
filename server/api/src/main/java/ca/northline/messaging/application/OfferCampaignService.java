package ca.northline.messaging.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.EmailLocales;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import ca.northline.email.EmailTemplates;
import ca.northline.identity.api.MarketAudience;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.OfferDraft;
import ca.northline.region.api.Markets;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.sms.SmsTransport;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-157: the console's offers ({@link OfferCampaigns}). The audience is computed at send time: customers of the market
 * (a saved address in the province, {@link MarketAudience}) whose newest consent for the channel is a grant. Sending
 * writes one {@code messaging.offer} row per person to the worker's deferred queue, {@code perMinute} a minute; the
 * worker re-reads the consent before each one (a withdrawal meanwhile cancels it), renders the CASL footer (legal
 * name, mailing address, unsubscribe link, List-Unsubscribe one-click) or the text's sender and opt-out link, and holds
 * texts in the person's quiet hours. Tests go only to the staff member's own address, marked "[Test]".
 */
@Slf4j
@Service
@Transactional
class OfferCampaignService implements OfferCampaigns {

    static final String NO_RECIPIENTS = "Nobody in this market has agreed to offers on this channel yet.";
    static final String TEST_NO_EMAIL = "Add an email address to your profile to get a test.";
    static final String TEST_NO_PHONE = "Add a mobile number to your profile to get a test.";
    static final URI SAMPLE_UNSUBSCRIBE = URI.create("https://example.invalid/unsubscribe?t=preview");

    private final OfferStore store;
    private final MarketAudience audience;
    private final Markets markets;
    private final NotificationContacts contacts;
    private final EmailTemplates templates;
    private final EmailSender emailSender;
    private final SmsTransport sms;
    private final AuditTrail audit;
    private final NotificationLinks links;
    private final UnsubscribeTokens unsubscribe;
    private final Clock clock;
    private final String consumerUrl;
    private final int perMinute;

    OfferCampaignService(
            OfferStore store,
            MarketAudience audience,
            Markets markets,
            NotificationContacts contacts,
            EmailTemplates templates,
            EmailSender emailSender,
            SmsTransport sms,
            AuditTrail audit,
            NotificationLinks links,
            UnsubscribeTokens unsubscribe,
            Clock clock,
            @Value("${northline.messaging.offers.consumer-url:${CONSUMER_ORIGIN:http://localhost:3000}}")
                    String consumerUrl,
            @Value("${northline.messaging.offers.per-minute:30}") int perMinute) {
        this.store = store;
        this.audience = audience;
        this.markets = markets;
        this.contacts = contacts;
        this.templates = templates;
        this.emailSender = emailSender;
        this.sms = sms;
        this.audit = audit;
        this.links = links;
        this.unsubscribe = unsubscribe;
        this.clock = clock;
        this.consumerUrl = consumerUrl.endsWith("/") ? consumerUrl.substring(0, consumerUrl.length() - 1) : consumerUrl;
        this.perMinute = Math.clamp(perMinute, 1, 50); // the worker's deferred job sends at most 50 a minute
    }

    @Override
    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(new TreeSet<>(markets.served()), perMinute, store.recent(50));
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(OfferDraft draft) {
        requireMarket(draft);
        return new Preview(
                render(draft, "en", SAMPLE_UNSUBSCRIBE),
                render(draft, "fr", SAMPLE_UNSUBSCRIBE),
                count(draft.market()));
    }

    @Override
    public void test(OfferDraft draft, String staffId, String staffRoles, String language) {
        requireMarket(draft);
        var me = contacts.contact(staffId).orElseThrow(() -> RuleViolation.of("channel", "required", TEST_NO_EMAIL));
        var locale = "fr".equals(language) ? EmailLocales.FRENCH : EmailLocales.ENGLISH;
        if (draft.email()) {
            var address = me.email();
            if (address == null || address.isBlank()) {
                throw RuleViolation.of("channel", "required", TEST_NO_EMAIL);
            }
            var link = links.unsubscribe(unsubscribe.issue(staffId, "consent.marketing_email", locale));
            var r = render(draft, language, link);
            emailSender.send(new EmailMessage(
                    EmailAddress.of(address), "[Test] " + r.subject(), r.html(), r.text(), Map.of(), "offer-test"));
        } else {
            var phone = me.phone();
            if (phone == null || phone.isBlank()) {
                throw RuleViolation.of("channel", "required", TEST_NO_PHONE);
            }
            var link = links.unsubscribe(unsubscribe.issue(staffId, "consent.marketing_sms", locale));
            sms.sendText(phone, "[Test] " + smsText(draft, language, link.toString()));
        }
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                staffRoles,
                "offer.test_sent",
                "offer",
                "test",
                null,
                Map.of("market", draft.market(), "channel", draft.channel())));
    }

    @Override
    public Campaign send(OfferDraft draft, String staffId, String staffRoles) {
        requireMarket(draft);
        var recipients =
                recipients(draft.market(), draft.channel()).stream().sorted().toList();
        if (recipients.isEmpty()) {
            throw new Conflict("no_recipients", NO_RECIPIENTS);
        }
        var id = Ids.next();
        var now = clock.instant();
        var finishes = store.enqueue(id, draft, recipients, now, perMinute);
        var campaign = new Campaign(
                id,
                draft.market(),
                draft.channel(),
                draft.titleEn(),
                draft.titleFr(),
                draft.path(),
                recipients.size(),
                recipients.size(),
                0,
                staffId,
                now,
                finishes);
        store.save(campaign, draft, perMinute);
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                staffRoles,
                "offer.sent",
                "offer_campaign",
                id,
                null,
                Map.of("market", draft.market(), "channel", draft.channel(), "recipients", recipients.size())));
        log.info(
                "Offer {} queued for {} customer(s) in {} by {}",
                id,
                recipients.size(),
                draft.market(),
                draft.channel());
        return campaign;
    }

    private void requireMarket(OfferDraft draft) {
        if (!markets.serves(draft.market())) {
            throw RuleViolation.of("market", "allowed", OfferDraft.MARKET);
        }
    }

    private Audience count(String market) {
        return new Audience(
                market,
                recipients(market, "email").size(),
                recipients(market, "sms").size());
    }

    private Set<String> recipients(String market, String channel) {
        var consenting =
                store.consentingCustomers(ConsentCategory.ofChannel(channel).code());
        return consenting.isEmpty() ? Set.of() : audience.inProvince(consenting, market);
    }

    private Rendered render(OfferDraft draft, String language, URI unsubscribeLink) {
        var fr = "fr".equals(language);
        var email = templates.render(
                new EmailContent.MarketingOffer(
                        fr ? draft.titleFr() : draft.titleEn(),
                        fr ? draft.bodyFr() : draft.bodyEn(),
                        URI.create(consumerUrl + draft.path())),
                fr ? EmailLocales.FRENCH : EmailLocales.ENGLISH,
                unsubscribeLink);
        var text = smsText(draft, language, unsubscribeLink.toString());
        return new Rendered(email.subject(), email.html(), email.text(), text, segments(text));
    }

    /**
     * The text as the worker sends it (PersonalNotices.offer + Deliveries.commercialSms): the brand, the title, the
     * message, then the legal sender, the opt-out link and the STOP keyword.
     */
    private String smsText(OfferDraft draft, String language, String optOut) {
        var fr = "fr".equals(language);
        var legal = templates.legalName();
        return (fr ? "Northline : " : "Northline: ") + (fr ? draft.titleFr() : draft.titleEn()) + ". "
                + (fr ? draft.bodyFr() : draft.bodyEn()) + " — " + legal
                + (fr
                        ? " · Désabonnement : " + optOut + " ou répondez ARRET"
                        : " · Opt out: " + optOut + " or reply STOP");
    }

    /** GSM-7 parts of 153 characters (160 alone); anything outside it makes the text UCS-2, 67 a part (70 alone). */
    static int segments(String text) {
        var gsm = text.chars().allMatch(c -> c < 128 && c != '`');
        var single = gsm ? 160 : 70;
        var part = gsm ? 153 : 67;
        var n = text.length();
        return n <= single ? 1 : (n + part - 1) / part;
    }
}
