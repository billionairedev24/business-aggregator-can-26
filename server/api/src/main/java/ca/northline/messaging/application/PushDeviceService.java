package ca.northline.messaging.application;

import ca.northline.messaging.application.PushDevices.Device;
import ca.northline.messaging.application.PushDevices.ManagePushDevices;
import ca.northline.messaging.application.PushDevices.PushDeviceStore;
import ca.northline.messaging.application.PushDevices.Registration;
import ca.northline.messaging.domain.PushDeviceRules;
import ca.northline.messaging.domain.PushDeviceRules.App;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The push device registry (S-102). Rows are keyed by person, app and installation, so one phone shared by two people
 * has a row each — but a token can only reach one of them: the latest to register it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class PushDeviceService implements ManagePushDevices {

    private final PushDeviceStore store;
    private final Clock clock;

    @Override
    public Device register(Registration r) {
        PushDeviceRules.installationId(r.installationId());
        if (PushDeviceRules.allowed(r.permission()) && r.token() == null) {
            throw RuleViolation.of("token", "required", PushDeviceRules.TOKEN_NEEDED);
        }
        var normalized = new Registration(
                r.userId(),
                r.app(),
                r.installationId(),
                r.platform(),
                r.token() == null ? null : r.token().strip(),
                PushDeviceRules.locale(r.locale()),
                r.appVersion().strip(),
                r.permission());
        return store.save(normalized, Ids.next(), clock.instant());
    }

    @Override
    public boolean remove(String userId, App app, String installationId) {
        var removed = store.delete(userId, app, PushDeviceRules.installationId(installationId));
        if (removed) {
            log.info("Push device {} of user {} removed ({} app)", installationId, userId, app.code());
        }
        return removed;
    }
}
