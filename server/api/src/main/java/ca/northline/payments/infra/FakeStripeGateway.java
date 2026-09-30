package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PayoutGateway;
import ca.northline.payments.domain.AuthorizationWindow;
import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.Zones;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in for Stripe when no secret key is configured (local, test, CI). Everything succeeds; ids look like Stripe's;
 * a PaymentIntent is authorized as soon as it exists (there is no card to confirm) and any {@code pi_…} it never saw
 * counts as authorized, except ids containing {@code requires_action}; instant payouts arrive in ~30 minutes, scheduled ones the next business morning and are paid
 * once that time passes. Financial Connections always links "RBC ··8820" (the design's example account).
 */
@Slf4j
@RequiredArgsConstructor
class FakeStripeGateway implements PaymentGateway, PayoutGateway {

    /** Canadian institution numbers → the names Stripe reports. */
    static final Map<String, String> INSTITUTIONS = Map.ofEntries(
            Map.entry("001", "BMO"),
            Map.entry("002", "Scotiabank"),
            Map.entry("003", "RBC"),
            Map.entry("004", "TD Canada Trust"),
            Map.entry("006", "National Bank"),
            Map.entry("010", "CIBC"),
            Map.entry("016", "HSBC"),
            Map.entry("219", "ATB Financial"),
            Map.entry("614", "Tangerine"),
            Map.entry("815", "Desjardins"),
            Map.entry("809", "Credit union"),
            Map.entry("828", "Credit union"),
            Map.entry("869", "Credit union"),
            Map.entry("899", "Credit union"));

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Authorization> intents = new ConcurrentHashMap<>();
    private final Map<String, Instant> payouts = new ConcurrentHashMap<>();

    private String id(String prefix, int length) {
        var sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Override
    public String customer(String customerId, String idempotencyKey) {
        return id("cus_", 14);
    }

    @Override
    public Authorization authorize(Authorize request) {
        var pi = id("pi_", 24);
        var authorization = new Authorization(
                pi,
                IntentStatus.AUTHORIZED,
                request.amountCents(),
                request.amountCents(),
                pi + "_secret_" + id("", 24),
                request.stripeCustomer(),
                request.paymentMethod() == null ? "pm_card_visa" : request.paymentMethod(),
                id("ch_", 24),
                clock.instant().plus(AuthorizationWindow.VALIDITY),
                request.transferGroup());
        intents.put(pi, authorization);
        return authorization;
    }

    @Override
    public Authorization authorization(String stripePaymentIntent) {
        if (stripePaymentIntent.contains("requires_action")) {
            return new Authorization(
                    stripePaymentIntent, IntentStatus.REQUIRES_ACTION, 0, 0, null, null, null, null, null, null);
        }
        return intents.getOrDefault(
                stripePaymentIntent,
                new Authorization(
                        stripePaymentIntent,
                        IntentStatus.AUTHORIZED,
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        clock.instant().plus(AuthorizationWindow.VALIDITY),
                        null));
    }

    @Override
    public String capture(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        log.debug("FAKE STRIPE capture {} {}¢", stripePaymentIntent, amountCents);
        var known = intents.get(stripePaymentIntent);
        return known != null && known.charge() != null ? known.charge() : id("ch_", 24);
    }

    @Override
    public void cancel(String stripePaymentIntent, String idempotencyKey) {
        log.debug("FAKE STRIPE cancel {}", stripePaymentIntent);
    }

    @Override
    public String transfer(Transfer transfer) {
        return id("tr_", 14);
    }

    @Override
    public String reverseTransfer(
            String stripeTransfer, long amountCents, Map<String, String> metadata, String idempotencyKey) {
        return id("trr_", 14);
    }

    @Override
    public String refund(
            String stripePaymentIntent, long amountCents, Map<String, String> metadata, String idempotencyKey) {
        return id("re_", 14);
    }

    @Override
    public Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String key) {
        var now = clock.instant();
        Instant arrives;
        if (instant) {
            arrives = now.plus(Duration.ofMinutes(30));
        } else {
            var day = LocalDate.ofInstant(now, Zones.EDMONTON).plusDays(1);
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                day = day.plusDays(1);
            }
            arrives = day.atTime(9, 0).atZone(Zones.EDMONTON).toInstant();
        }
        var id = id("po_", 4);
        payouts.put(id, arrives);
        return new Sent(id, arrives);
    }

    @Override
    public String recoverFee(String connectedAccount, long feeCents, String stripePayout, String idempotencyKey) {
        return id("tr_", 14);
    }

    /** Paid once the arrival time passed (payouts it never made — seeds, tests — are paid). */
    @Override
    public Payout.State payoutState(String connectedAccount, String stripePayout) {
        var arrives = payouts.get(stripePayout);
        return arrives == null || !arrives.isAfter(clock.instant()) ? Payout.State.PAID : Payout.State.IN_TRANSIT;
    }

    @Override
    public void useManualPayouts(String connectedAccount) {
        log.debug("FAKE STRIPE manual payouts for {}", connectedAccount);
    }

    @Override
    public LinkSession startBankLink(String connectedAccount) {
        return new LinkSession("fake", null, null);
    }

    @Override
    public BankAccount linked(String connectedAccount, String linkedAccountRef) {
        return new BankAccount(id("ba_", 14), "RBC", "003", null, "8820");
    }

    @Override
    public BankAccount manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName) {
        return new BankAccount(
                id("ba_", 14),
                INSTITUTIONS.getOrDefault(institution, "Institution " + institution),
                institution,
                transit,
                accountNumber.substring(accountNumber.length() - 4));
    }

    @Override
    public void makeDefault(String connectedAccount, String externalRef) {
        log.debug("FAKE STRIPE default external account {} → {}", connectedAccount, externalRef);
    }
}
