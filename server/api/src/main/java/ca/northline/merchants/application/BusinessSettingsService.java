package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.application.SettingsUseCases.UpdateBusinessSettings;
import ca.northline.merchants.application.SettingsUseCases.ViewBusinessSettings;
import ca.northline.merchants.domain.BusinessSettings;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.GstNumber;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings › Business. The display name goes through {@link RenameMerchant} (publishes {@code merchant.renamed}); a
 * changed legal name or GST number re-opens the registry / GST check for trust &amp; safety to re-verify.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class BusinessSettingsService implements ViewBusinessSettings, UpdateBusinessSettings {

    private final BusinessSettingsStore store;
    private final RenameMerchant rename;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public BusinessSettings view(String merchantId) {
        return store.find(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    @Override
    @Transactional
    public BusinessSettings update(UpdateBusinessSettings.Command command) {
        var actor = command.actor();
        var current = view(actor.merchantId());
        var gst = command.gstNumber() == null || command.gstNumber().isBlank()
                ? null
                : new GstNumber(command.gstNumber());
        var next = new BusinessSettings(
                        current.merchantId(),
                        current.type(),
                        current.structure(),
                        new DisplayName(command.displayName()),
                        command.legalName(),
                        gst,
                        command.serviceArea(),
                        BusinessSettings.CancellationPolicy.parse(command.cancellationPolicy()),
                        command.autoAcceptQuoteCents(),
                        command.languages(),
                        current.storeSlug())
                .validated();
        var now = clock.instant();
        rename.rename(new RenameMerchant.Command(
                actor.merchantId(), next.displayName().value(), actor.userId()));
        store.save(next, now);
        var changed = new ArrayList<String>();
        if (!next.displayName().equals(current.displayName())) {
            changed.add("displayName");
        }
        if (!next.legalName().equals(current.legalName())) {
            changed.add("legalName");
            store.reopenCheck(actor.merchantId(), "registry", now);
        }
        if (!Objects.equals(next.gstNumber(), current.gstNumber())) {
            changed.add("gstNumber");
            store.reopenCheck(actor.merchantId(), "gst", now);
        }
        if (!Objects.equals(next.serviceArea(), current.serviceArea())) {
            changed.add("serviceArea");
        }
        if (next.cancellationPolicy() != current.cancellationPolicy()) {
            changed.add("cancellationPolicy");
        }
        if (!Objects.equals(next.autoAcceptQuoteCents(), current.autoAcceptQuoteCents())) {
            changed.add("autoAcceptQuoteCents");
        }
        if (!next.languages().equals(current.languages())) {
            changed.add("languages");
        }
        if (!changed.isEmpty()) {
            audit.record(AuditTrail.Entry.of(
                            actor.merchantId(),
                            actor.userId(),
                            actor.role().code(),
                            "business.updated",
                            "merchant",
                            actor.merchantId())
                    .withChange(null, Map.of("fields", changed)));
        }
        return view(actor.merchantId());
    }
}
