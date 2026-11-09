package ca.northline.support;

import ca.northline.region.api.Regions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-190: live markets of the region model for a test (the shared database's own markets are pilot / waitlist since
 * V345). {@link #open} makes a made-up market live with a centre; {@link #close} turns every one it opened off again,
 * so the shared database looks the same to every other test class.
 */
public final class TestMarkets {

    private final JdbcClient jdbc;
    private final Regions regions;
    private final List<String> opened = new ArrayList<>();

    public TestMarkets(JdbcClient jdbc, Regions regions) {
        this.jdbc = jdbc;
        this.regions = regions;
    }

    /** A live market {@code city} in the first live province, centred at the point; returns the city. */
    public String open(String city, double lat, double lng) {
        var id = "mkt-test-" + city.toLowerCase(Locale.ROOT).replace(' ', '-');
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center,
                                                    radius_km, languages, sort)
                        select :id, 'market', p.id, p.province, :city, jsonb_build_object('en', :city, 'fr', :city),
                               'live', ST_GeogFromText(:point), 20, '{en,fr}', 90
                          from region.regions p
                         where p.kind = 'province' and p.stage = 'live'
                         order by p.sort limit 1
                        on conflict (id) do update set stage = 'live', center = excluded.center
                        """)
                .param("id", id)
                .param("city", city)
                .param("point", "SRID=4326;POINT(%s %s)".formatted(lng, lat))
                .update();
        opened.add(id);
        regions.refresh();
        return city;
    }

    public void close() {
        if (opened.isEmpty()) {
            return;
        }
        jdbc.sql("update region.regions set stage = 'off' where id in (:ids)")
                .param("ids", opened)
                .update();
        opened.clear();
        regions.refresh();
    }
}
