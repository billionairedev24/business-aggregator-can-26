package ca.northline.golive.domain;

import ca.northline.shared.CodedEnum;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The go-live gates of a market (docs/runbooks/go-live.md § Gates), in checklist order. {@link Kind#AUTO} gates are
 * evaluated by the platform on every read; {@link Kind#MANUAL} ones are recorded by staff (or by
 * {@code make go-live-check RECORD=1}, which checks what lives in the repository); {@link Kind#AUTO_OR_MANUAL} ones are
 * automatic where their source is configured (a metrics backend) and recorded otherwise.
 *
 * <p>Each gate has an owner (who answers for it, a code the console words: {@code sre}, {@code payments} …; the people
 * are in the runbook's contacts), whether it is required (a required gate that doesn't clear blocks the launch unless
 * overridden) and the runbook with its procedure, from the repository root.
 */
public enum Gate implements CodedEnum {
    MARKET_ZONES(Kind.AUTO, "operations", true, "docs/runbooks/regions.md"),
    PROVINCE_LIVE(Kind.AUTO, "operations", true, "docs/runbooks/regions.md"),
    UAT_GO_NO_GO(Kind.AUTO, "product", true, "docs/uat/README.md"),
    PILOT_BUSINESSES(Kind.AUTO, "merchant_success", true, "docs/runbooks/pilot-onboarding.md"),
    SECURITY_FINDINGS(Kind.MANUAL, "security", true, "docs/security/findings.md"),
    PENTEST(Kind.MANUAL, "security", true, "docs/security/pentest-scope.md"),
    BACKUP_DRILL(Kind.MANUAL, "sre", true, "docs/runbooks/backups-dr.md"),
    ALERT_RULES(Kind.AUTO_OR_MANUAL, "sre", true, "docs/runbooks/alerting.md"),
    ONCALL_COVERAGE(Kind.AUTO, "sre", true, "docs/runbooks/alerting.md"),
    SLO_ALERTS(Kind.AUTO_OR_MANUAL, "sre", true, "docs/runbooks/alerting.md"),
    LEGAL_SIGNOFF(Kind.MANUAL, "legal", true, "docs/compliance/legal/review-packet.md"),
    PCI_SAQ_A(Kind.MANUAL, "payments", true, "docs/compliance/pci/saq-a.md"),
    STRIPE_LIVE(Kind.AUTO, "payments", true, "docs/runbooks/stripe.md"),
    STRIPE_WEBHOOKS(Kind.MANUAL, "payments", true, "docs/runbooks/stripe.md"),
    DNS_CERTS(Kind.MANUAL, "sre", true, "docs/runbooks/edge.md"),
    APP_STORES(Kind.MANUAL, "mobile", false, "docs/runbooks/mobile-release.md"),
    /** Required only where the market's language rules are French-first (S-116); not applicable elsewhere. */
    FRENCH_COVERAGE(Kind.MANUAL, "localization", true, "docs/runbooks/i18n.md"),
    A11Y_CRITICALS(Kind.MANUAL, "qa", true, "docs/a11y/audit.md"),
    E2E_RESULTS(Kind.MANUAL, "qa", true, "docs/runbooks/e2e.md"),
    LOAD_TEST(Kind.MANUAL, "sre", true, "docs/runbooks/load-testing.md");

    public enum Kind implements CodedEnum {
        AUTO,
        MANUAL,
        AUTO_OR_MANUAL
    }

    private final Kind kind;
    private final String owner;
    private final boolean required;
    private final String runbook;

    Gate(Kind kind, String owner, boolean required, String runbook) {
        this.kind = kind;
        this.owner = owner;
        this.required = required;
        this.runbook = runbook;
    }

    public Kind kind() {
        return kind;
    }

    public String owner() {
        return owner;
    }

    public boolean required() {
        return required;
    }

    public String runbook() {
        return runbook;
    }

    public static Optional<Gate> fromCode(@Nullable String code) {
        return Arrays.stream(values()).filter(g -> g.code().equals(code)).findFirst();
    }
}
