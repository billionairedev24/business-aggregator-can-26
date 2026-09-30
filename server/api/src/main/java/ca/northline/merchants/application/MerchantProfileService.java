package ca.northline.merchants.application;

import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.Merchant;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the profile use cases. The state change and the event publication commit in one transaction — the
 * Modulith JDBC registry ({@code events.event_publication}) is the outbox that later externalizes to Kafka.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MerchantProfileService implements ViewMerchant, RenameMerchant, BusinessNames {

    private final MerchantRepository merchants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public Merchant view(String merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    @Override
    public Optional<String> displayName(String merchantId) {
        return merchants.findById(merchantId).map(m -> m.getDisplayName().value());
    }

    @Override
    @Transactional
    public Merchant rename(Command command) {
        var merchant = view(command.merchantId());
        merchant.rename(new DisplayName(command.displayName()), command.actorId(), clock.instant())
                .ifPresent(renamed -> {
                    merchants.save(merchant);
                    events.publishEvent(renamed);
                });
        return merchant;
    }
}
