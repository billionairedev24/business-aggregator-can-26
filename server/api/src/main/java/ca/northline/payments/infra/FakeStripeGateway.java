package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PayoutGateway;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.payments.domain.Zones;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in for Stripe when no secret key is configured (local, test, CI). Everything succeeds; ids look like Stripe's;
 * instant payouts arrive in ~30 minutes, scheduled ones the next business morning. Financial Connections always
 * links "RBC ··8820" (the design's example account).
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

    private String id(String prefix, int length) {
        var sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Override
    public void capture(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        log.debug("FAKE STRIPE capture {} {}¢", stripePaymentIntent, amountCents);
    }

    @Override
    public String transfer(String connectedAccount, long netCents, String escrowId, String idempotencyKey) {
        return id("tr_", 14);
    }

    @Override
    public String refund(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        return id("re_", 14);
    }

    @Override
    public Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String key) {
        var now = clock.instant();
        if (instant) {
            return new Sent(id("po_", 4), now.plus(Duration.ofMinutes(30)));
        }
        var day = LocalDate.ofInstant(now, Zones.EDMONTON).plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return new Sent(id("po_", 4), day.atTime(9, 0).atZone(Zones.EDMONTON).toInstant());
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

    @Override
    public void updateSchedule(String connectedAccount, PayoutSchedule schedule) {
        log.debug("FAKE STRIPE payout schedule {} → {}", connectedAccount, schedule);
    }
}
