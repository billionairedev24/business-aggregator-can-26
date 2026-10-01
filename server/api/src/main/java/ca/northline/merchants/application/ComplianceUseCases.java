package ca.northline.merchants.application;

import ca.northline.merchants.application.ConnectAccountGateway.Requirement;
import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Inbound ports of Stripe &amp; compliance and the screen's read model. */
public final class ComplianceUseCases {
    private ComplianceUseCases() {}

    public interface ViewCompliance {
        ComplianceView view(String merchantId);
    }

    public interface RenewVerification {
        record Command(SettingsActor actor, String verificationId, String fileName, String contentType, Bytes bytes) {}

        ComplianceItem renew(Command command);
    }

    public interface AcceptObligations {
        ObligationsView accept(SettingsActor actor, String version);
    }

    public interface OpenStripeLink {
        /** {@code dashboard} (Express dashboard) or {@code update} (hosted onboarding: identity document, bank). */
        String link(SettingsActor actor, String kind);
    }

    /** Everything the Stripe &amp; compliance screen shows. */
    public record ComplianceView(
            Business business,
            StripeView stripe,
            String taxPeriod,
            List<TaxRow> tax,
            List<ComplianceItem> documents,
            ObligationsView obligations,
            int dueCount) {}

    /**
     * @param requiredFor first approved category ("Mobile mechanic") — "Required for Mobile mechanic · {province}"
     */
    public record Business(
            String type,
            String displayName,
            String legalName,
            @Nullable String businessNumber,
            @Nullable String province,
            @Nullable String requiredFor,
            @Nullable String ownerName,
            @Nullable Integer takeRateBps) {}

    /**
     * @param status {@code connected} | {@code not_connected} | {@code unavailable} (Stripe could not be reached)
     * @param accountId masked, "acct_1Kx9…Q2"
     */
    public record StripeView(
            String status,
            @Nullable String accountId,
            @Nullable String type,
            boolean chargesEnabled,
            boolean payoutsEnabled,
            List<Requirement> requirements,
            @Nullable String bankLabel,
            @Nullable String statementDescriptor,
            @Nullable String payoutInterval,
            @Nullable String payoutWeekday,
            boolean instantPayouts) {}

    /**
     * @param handling {@code remitted_by_northline} | {@code not_selling} | {@code charged_on_invoice}
     */
    public record TaxRow(String jurisdiction, long collectedCents, String handling) {}

    public record ObligationsView(
            String currentVersion,
            @Nullable String acceptedVersion,
            @Nullable Instant acceptedAt,
            boolean upToDate) {}
}
