package ca.northline.payments.infra;

import ca.northline.payments.application.BankLinking;
import ca.northline.shared.Ids;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Bank linking without Stripe (no key configured: local, test, CI). The session's mode is {@code fake}: the Studio
 * shows a simulated bank picker instead of loading Stripe.js, and sends a token {@code btok_local_<institution>_<last4>}
 * with a {@code fca_local_…} account — the design's "RBC ··8820" is the first choice. Typed details work as at Stripe
 * (institution name from the number, last 4 digits kept). Nothing leaves the process.
 */
@Slf4j
class FakeBankLinking implements BankLinking {

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

    private static final Pattern TOKEN = Pattern.compile("btok_local_(\\d{3})_(\\d{4})");

    @Override
    public LinkSession start(String connectedAccount) {
        return new LinkSession("fake", null, null);
    }

    @Override
    public Linked link(String connectedAccount, String bankToken, @Nullable String financialConnectionsAccount) {
        var m = TOKEN.matcher(bankToken);
        if (!m.matches() || !INSTITUTIONS.containsKey(m.group(1))) {
            throw new NotLinkable("not a local bank token: " + bankToken);
        }
        if (financialConnectionsAccount != null && !financialConnectionsAccount.startsWith("fca_local_")) {
            throw new NotLinkable("not a local Financial Connections account: " + financialConnectionsAccount);
        }
        log.debug("FAKE Financial Connections: {} linked {} ··{}", connectedAccount, m.group(1), m.group(2));
        return new Linked(
                "ba_local_" + Ids.next(),
                INSTITUTIONS.get(m.group(1)),
                m.group(2),
                null,
                null,
                financialConnectionsAccount == null ? "fca_local_" + Ids.next() : financialConnectionsAccount);
    }

    @Override
    public Linked manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName) {
        return new Linked(
                "ba_local_" + Ids.next(),
                INSTITUTIONS.getOrDefault(institution, "Institution " + institution),
                accountNumber.substring(accountNumber.length() - 4),
                institution,
                transit,
                null);
    }
}
