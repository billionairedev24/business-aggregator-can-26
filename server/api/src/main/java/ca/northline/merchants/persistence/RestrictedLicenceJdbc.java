package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.api.RestrictedLicences.Licence;
import ca.northline.merchants.application.RestrictedLicenceStore;
import ca.northline.region.api.AgeClass;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.MerchantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link RestrictedLicenceStore} over {@code merchants.restricted_licences} (V342). */
@Repository
@RequiredArgsConstructor
class RestrictedLicenceJdbc implements RestrictedLicenceStore {

    private static final String COLUMNS = """
            l.id, l.merchant_id, l.age_class, l.province, l.licence_number, l.document_id, l.expires_on, l.status,
            l.submitted_at, l.decided_at, l.reject_reason, l.note""";

    private final JdbcClient jdbc;

    @Override
    public void insert(Licence l, String submittedBy) {
        jdbc.sql("""
                        insert into merchants.restricted_licences
                          (id, merchant_id, age_class, province, licence_number, document_id, expires_on, status,
                           submitted_by, submitted_at)
                        values (:id, :m, :c, :p, :n, :d, :e, 'pending', :by, :at)
                        """)
                .param("id", l.id())
                .param("m", l.merchantId())
                .param("c", l.ageClass().code())
                .param("p", l.province())
                .param("n", l.licenceNumber())
                .param("d", l.documentId())
                .param("e", l.expiresOn())
                .param("by", submittedBy)
                .param("at", ts(l.submittedAt()))
                .update();
    }

    @Override
    public Optional<Licence> lock(String licenceId) {
        return jdbc.sql("select " + COLUMNS + " from merchants.restricted_licences l where l.id = :id for update")
                .param("id", licenceId)
                .query((rs, _) -> licence(rs))
                .optional();
    }

    @Override
    public Optional<QueueRow> row(String licenceId) {
        return jdbc.sql("select " + COLUMNS + """
                        , m.display_name, m.province as business_province
                          from merchants.restricted_licences l join merchants.merchants m on m.id = l.merchant_id
                         where l.id = :id
                        """)
                .param("id", licenceId)
                .query((rs, _) -> queueRow(rs))
                .optional();
    }

    @Override
    public List<Licence> of(String merchantId) {
        return jdbc.sql("select " + COLUMNS
                        + " from merchants.restricted_licences l where l.merchant_id = :m order by l.submitted_at desc")
                .param("m", merchantId)
                .query((rs, _) -> licence(rs))
                .list();
    }

    @Override
    public Set<AgeClass> licensedClasses(String merchantId, LocalDate today) {
        var codes = jdbc.sql("""
                        select distinct l.age_class
                          from merchants.restricted_licences l join merchants.merchants m on m.id = l.merchant_id
                         where l.merchant_id = :m and l.status = 'approved' and l.expires_on >= :today
                           and l.province = m.province
                        """)
                .param("m", merchantId)
                .param("today", today)
                .query(String.class)
                .list();
        var out = EnumSet.noneOf(AgeClass.class);
        codes.forEach(c -> AgeClass.of(c).ifPresent(out::add));
        return Set.copyOf(out);
    }

    @Override
    public void decide(
            String licenceId,
            String status,
            String staffId,
            Instant at,
            @Nullable String reason,
            @Nullable String note) {
        jdbc.sql("""
                        update merchants.restricted_licences
                           set status = :s, decided_by = :by, decided_at = :at, reject_reason = :r, note = :note
                         where id = :id
                        """)
                .param("id", licenceId)
                .param("s", status)
                .param("by", staffId)
                .param("at", ts(at))
                .param("r", reason)
                .param("note", note)
                .update();
    }

    @Override
    public int replaceOthers(String merchantId, AgeClass ageClass, String keep) {
        return jdbc.sql("""
                        update merchants.restricted_licences set status = 'replaced'
                         where merchant_id = :m and age_class = :c and status = 'approved' and id <> :keep
                        """)
                .param("m", merchantId)
                .param("c", ageClass.code())
                .param("keep", keep)
                .update();
    }

    @Override
    public List<Licence> dueToExpire(LocalDate today, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from merchants.restricted_licences l
                        where l.status = 'approved' and l.expires_on < :today
                        order by l.expires_on limit :limit for update skip locked
                        """)
                .param("today", today)
                .param("limit", limit)
                .query((rs, _) -> licence(rs))
                .list();
    }

    @Override
    public void expire(String licenceId) {
        jdbc.sql("update merchants.restricted_licences set status = 'expired' where id = :id")
                .param("id", licenceId)
                .update();
    }

    @Override
    public List<Licence> dueForReminder(LocalDate by, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from merchants.restricted_licences l
                        where l.status = 'approved' and l.reminded_at is null and l.expires_on <= :by
                          and not exists (select 1 from merchants.restricted_licences n
                                           where n.merchant_id = l.merchant_id and n.age_class = l.age_class
                                             and n.status = 'pending')
                        order by l.expires_on limit :limit for update skip locked
                        """)
                .param("by", by)
                .param("limit", limit)
                .query((rs, _) -> licence(rs))
                .list();
    }

    @Override
    public void reminded(String licenceId, Instant at) {
        jdbc.sql("update merchants.restricted_licences set reminded_at = :at where id = :id")
                .param("id", licenceId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<QueueRow> queue(MerchantScope scope, @Nullable String status, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                        , m.display_name, m.province as business_province
                          from merchants.restricted_licences l join merchants.merchants m on m.id = l.merchant_id
                         where (cast(:status as text) is null or l.status = cast(:status as text))
                           and (:everyone or l.merchant_id = any(:merchants))
                         order by case when l.status = 'pending' then l.submitted_at end asc nulls last,
                                  coalesce(l.decided_at, l.submitted_at) desc
                         limit :limit
                        """)
                .param("status", status)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("limit", limit)
                .query((rs, _) -> queueRow(rs))
                .list();
    }

    @Override
    public @Nullable String province(String merchantId) {
        return jdbc.sql("select province from merchants.merchants where id = :m")
                .param("m", merchantId)
                .query((rs, _) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    private static QueueRow queueRow(ResultSet rs) throws SQLException {
        var name = rs.getString("display_name");
        return new QueueRow(licence(rs), name == null ? "" : name, rs.getString("business_province"));
    }

    private static Licence licence(ResultSet rs) throws SQLException {
        return new Licence(
                rs.getString("id"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(AgeClass.class, rs.getString("age_class")),
                rs.getString("province"),
                rs.getString("licence_number"),
                rs.getString("document_id"),
                rs.getObject("expires_on", LocalDate.class),
                rs.getString("status"),
                requiredInstant(rs, "submitted_at"),
                instant(rs, "decided_at"),
                rs.getString("reject_reason"),
                rs.getString("note"));
    }
}
