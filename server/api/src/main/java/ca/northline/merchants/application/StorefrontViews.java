package ca.northline.merchants.application;

import ca.northline.merchants.application.StorefrontUseCases.DnsRecord;
import ca.northline.merchants.application.StorefrontUseCases.DomainSetup;
import ca.northline.merchants.application.StorefrontUseCases.StorefrontView;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Storefront;
import ca.northline.shared.NotFound;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds {@link StorefrontView}s: the page, its business, logo, verified facts and custom-domain instructions. */
@Component
@RequiredArgsConstructor
class StorefrontViews {

    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final DocumentRepository documents;
    private final DomainSettings settings;

    StorefrontView of(Storefront storefront) {
        var merchant = merchant(storefront.getMerchantId());
        var logoId = storefront.getLogoDocumentId();
        var logo = logoId == null
                ? null
                : documents.find(storefront.getMerchantId(), logoId).orElse(null);
        var claim = storefront.getDomainClaim();
        return new StorefrontView(
                storefront,
                merchant,
                logo,
                verifications.listFor(storefront.getMerchantId()),
                settings.targetHost(),
                claim == null ? null : setup(claim));
    }

    MerchantApplication merchant(String merchantId) {
        return applications.findById(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    /**
     * A subdomain gets a CNAME to {@code pages.<zone>}. An apex can't hold a CNAME: A/AAAA records to the edge's fixed
     * addresses when the environment has them ({@code DOMAINS_EDGE_ADDRESSES}), otherwise an ALIAS/ANAME (or CNAME
     * flattening) to {@code pages.<zone>}. Always the ownership TXT record.
     */
    DomainSetup setup(DomainClaim claim) {
        var domain = claim.domain();
        var target = settings.targetHost();
        var records = new ArrayList<DnsRecord>();
        if (!domain.isApex()) {
            records.add(new DnsRecord("CNAME", domain.value(), target));
        } else if (settings.edgeAddresses().isEmpty()) {
            records.add(new DnsRecord("ALIAS", domain.value(), target));
        } else {
            settings.edgeAddresses()
                    .forEach(a -> records.add(new DnsRecord(a.contains(":") ? "AAAA" : "A", domain.value(), a)));
        }
        records.add(new DnsRecord("TXT", domain.verificationName(), claim.token()));
        return new DomainSetup(target, domain.isApex(), records, claim.graceEndsAt(settings.policy()));
    }
}
