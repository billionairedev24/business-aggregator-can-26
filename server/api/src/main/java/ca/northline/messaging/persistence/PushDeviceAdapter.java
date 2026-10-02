package ca.northline.messaging.persistence;

import ca.northline.messaging.application.PushDevices.Device;
import ca.northline.messaging.application.PushDevices.PushDeviceStore;
import ca.northline.messaging.application.PushDevices.Registration;
import ca.northline.messaging.domain.PushDeviceRules.App;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PushDeviceStore}: {@code messaging.push_devices} (V245). The worker reads it and deletes dead tokens. */
@Repository
@RequiredArgsConstructor
class PushDeviceAdapter implements PushDeviceStore {

    private final JdbcClient jdbc;

    @Override
    public Device save(Registration r, String newId, Instant now) {
        if (r.token() != null) {
            jdbc.sql("""
                            delete from messaging.push_devices
                             where app = :app and platform = :platform and token = :token
                               and not (user_id = :user and installation_id = :installation)
                            """)
                    .param("app", r.app().code())
                    .param("platform", r.platform())
                    .param("token", r.token())
                    .param("user", r.userId())
                    .param("installation", r.installationId())
                    .update();
        }
        var at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                        insert into messaging.push_devices (id, user_id, app, installation_id, platform, token, locale,
                               app_version, permission, created_at, refreshed_at)
                        values (:id, :user, :app, :installation, :platform, :token, :locale, :version, :permission,
                               :at, :at)
                        on conflict (user_id, app, installation_id) do update set platform = excluded.platform,
                               token = excluded.token, locale = excluded.locale, app_version = excluded.app_version,
                               permission = excluded.permission, refreshed_at = excluded.refreshed_at
                        """)
                .param("id", newId)
                .param("user", r.userId())
                .param("app", r.app().code())
                .param("installation", r.installationId())
                .param("platform", r.platform())
                .param("token", r.token())
                .param("locale", r.locale())
                .param("version", r.appVersion())
                .param("permission", r.permission())
                .param("at", at)
                .update();
        return new Device(r.installationId(), r.app(), r.platform(), r.locale(), r.permission(), now);
    }

    @Override
    public boolean delete(String userId, App app, String installationId) {
        return jdbc.sql("""
                        delete from messaging.push_devices
                         where user_id = :user and app = :app and installation_id = :installation
                        """)
                        .param("user", userId)
                        .param("app", app.code())
                        .param("installation", installationId)
                        .update()
                > 0;
    }
}
