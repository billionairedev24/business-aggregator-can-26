package ca.northline.worker.push;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The worker's side of the device registry ({@code messaging.push_devices}, V245; the api writes it): who can get a
 * push, and the deletions the providers ask for. Same database, like the notifications' other read models.
 */
public class PushDeviceStore {

    private final JdbcClient jdbc;

    public PushDeviceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The person's installations of {@code app} that allow notifications and refreshed after {@code freshAfter}. */
    public List<PushDevice> reachable(String userId, String app, Instant freshAfter) {
        return jdbc.sql("""
                        select id, user_id, app, platform, token, locale from messaging.push_devices
                         where user_id = :user and app = :app and token is not null
                           and permission in ('granted', 'provisional') and refreshed_at > :fresh
                         order by refreshed_at desc""")
                .param("user", userId)
                .param("app", app)
                .param("fresh", OffsetDateTime.ofInstant(freshAfter, ZoneOffset.UTC))
                .query((rs, _) -> new PushDevice(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("app"),
                        rs.getString("platform"),
                        rs.getString("token"),
                        rs.getString("locale").startsWith("fr") ? "fr" : "en"))
                .list();
    }

    /** The provider said the token is gone (app uninstalled, token rotated, wrong app). */
    public void delete(String id) {
        jdbc.sql("delete from messaging.push_devices where id = :id")
                .param("id", id)
                .update();
    }

    /** Installations not refreshed since {@code before} (the app is gone or signed out without telling us). */
    public int prune(Instant before) {
        return jdbc.sql("delete from messaging.push_devices where refreshed_at < :before")
                .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .update();
    }
}
