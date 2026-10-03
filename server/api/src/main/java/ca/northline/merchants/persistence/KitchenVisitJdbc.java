package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.KitchenVisitStore;
import ca.northline.merchants.domain.KitchenVisit;
import ca.northline.shared.CodedEnum;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link KitchenVisitStore} over {@code merchants.kitchen_visits} (V321). */
@Repository
@RequiredArgsConstructor
class KitchenVisitJdbc implements KitchenVisitStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VISIT = """
            select id, merchant_id, scheduled_at, inspector_id, inspector_name, status, checklist::text as checklist,
                   note, photo_ids, scheduled_by, recorded_by, recorded_at
              from merchants.kitchen_visits
            """;

    private final JdbcClient jdbc;

    @Override
    public void insert(KitchenVisit v) {
        jdbc.sql("""
                        insert into merchants.kitchen_visits (id, merchant_id, scheduled_at, inspector_id,
                               inspector_name, status, scheduled_by)
                        values (:id, :m, :at, :inspector, :name, :status, :by)
                        """)
                .param("id", v.id())
                .param("m", v.merchantId())
                .param("at", ts(v.scheduledAt()))
                .param("inspector", v.inspectorId())
                .param("name", v.inspectorName())
                .param("status", v.status().code())
                .param("by", v.scheduledBy())
                .update();
    }

    @Override
    public void update(KitchenVisit v) {
        var checklist = new LinkedHashMap<String, String>();
        v.checklist().forEach((item, mark) -> checklist.put(item.code(), mark.code()));
        jdbc.sql("""
                        update merchants.kitchen_visits
                           set status = :status, checklist = cast(:checklist as jsonb), note = :note,
                               photo_ids = :photos, recorded_by = :by, recorded_at = :at, updated_at = now()
                         where id = :id and merchant_id = :m
                        """)
                .param("status", v.status().code())
                .param("checklist", JSON.writeValueAsString(checklist))
                .param("note", v.note())
                .param("photos", v.photoIds().toArray(String[]::new))
                .param("by", v.recordedBy())
                .param("at", ts(v.recordedAt()))
                .param("id", v.id())
                .param("m", v.merchantId())
                .update();
    }

    @Override
    public Optional<KitchenVisit> lock(String merchantId, String visitId) {
        return jdbc.sql(VISIT + " where id = :id and merchant_id = :m for update")
                .param("id", visitId)
                .param("m", merchantId)
                .query((rs, _) -> visit(rs))
                .optional();
    }

    @Override
    public List<KitchenVisit> visits(String merchantId) {
        return jdbc.sql(VISIT + " where merchant_id = :m order by scheduled_at desc, id desc")
                .param("m", merchantId)
                .query((rs, _) -> visit(rs))
                .list();
    }

    @Override
    public Map<String, KitchenVisit> latest(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        select distinct on (merchant_id) id, merchant_id, scheduled_at, inspector_id, inspector_name,
                               status, checklist::text as checklist, note, photo_ids, scheduled_by, recorded_by,
                               recorded_at
                          from merchants.kitchen_visits
                         where merchant_id = any(:m) and status <> 'cancelled'
                         order by merchant_id, scheduled_at desc, id desc
                        """).param("m", merchantIds.toArray(String[]::new)).query((rs, _) -> visit(rs)).list().stream()
                .collect(Collectors.toUnmodifiableMap(KitchenVisit::merchantId, v -> v));
    }

    private static KitchenVisit visit(ResultSet rs) throws SQLException {
        var marks = new EnumMap<KitchenVisit.Item, KitchenVisit.Mark>(KitchenVisit.Item.class);
        var json = rs.getString("checklist");
        if (json != null) {
            JSON.readTree(json)
                    .properties()
                    .forEach(e -> marks.put(
                            CodedEnum.fromCode(KitchenVisit.Item.class, e.getKey()),
                            CodedEnum.fromCode(
                                    KitchenVisit.Mark.class, e.getValue().asString())));
        }
        return new KitchenVisit(
                rs.getString("id"),
                rs.getString("merchant_id"),
                requiredInstant(rs, "scheduled_at"),
                rs.getString("inspector_id"),
                rs.getString("inspector_name"),
                CodedEnum.fromCode(KitchenVisit.Status.class, rs.getString("status")),
                marks,
                rs.getString("note"),
                strings(rs.getArray("photo_ids")),
                rs.getString("scheduled_by"),
                rs.getString("recorded_by"),
                instant(rs, "recorded_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
