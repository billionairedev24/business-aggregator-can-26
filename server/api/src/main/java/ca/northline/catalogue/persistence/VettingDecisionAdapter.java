package ca.northline.catalogue.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.catalogue.application.VettingDecisionStore;
import ca.northline.shared.MerchantScope;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link VettingDecisionStore} over {@code catalogue.offers ∪ services} and {@code catalogue.vetting_decisions}. */
@Repository
@RequiredArgsConstructor
class VettingDecisionAdapter implements VettingDecisionStore {

    private static final String LISTINGS = """
            (select id, merchant_id, vetting, vetting_flags, submitted_at from catalogue.offers
             union all
             select id, merchant_id, vetting, vetting_flags, submitted_at from catalogue.services) l""";

    private final JdbcClient jdbc;

    @Override
    public List<String> queueIds(MerchantScope scope, Collection<String> alsoIds, Instant decidedSince, int limit) {
        return jdbc.sql("""
                        select l.id from %s
                          left join lateral (select max(decided_at) as decided_at from catalogue.vetting_decisions d
                                              where d.listing_id = l.id) d on true
                         where ((:everyone or l.merchant_id = any(:merchants))
                                and ((l.vetting = 'pending' and cardinality(l.vetting_flags) > 0)
                                     or d.decided_at >= :since))
                            or l.id = any(:also)
                         order by (l.vetting = 'pending') desc, l.submitted_at asc nulls last, d.decided_at desc, l.id
                         limit :n
                        """.formatted(LISTINGS))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(decidedSince))
                .param("also", alsoIds.toArray(String[]::new))
                .param("n", limit)
                .query((rs, _) -> rs.getString("id"))
                .list();
    }

    @Override
    public long autoApproved(MerchantScope scope, Instant since) {
        return jdbc.sql("""
                        select count(*) from %s
                         where l.vetting = 'approved' and l.submitted_at >= :since
                           and (:everyone or l.merchant_id = any(:merchants))
                           and not exists (select 1 from catalogue.vetting_decisions d where d.listing_id = l.id
                                             and d.decided_at >= l.submitted_at)
                        """.formatted(LISTINGS))
                .param("since", ts(since))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public void insert(Decision d) {
        jdbc.sql("""
                        insert into catalogue.vetting_decisions (id, listing_id, kind, merchant_id, decision, reasons,
                               flags, note, decided_by, role, decided_at)
                        values (:id, :listing, :kind, :m, :decision, :reasons, :flags, :note, :by, :role, :at)
                        """)
                .param("id", d.id())
                .param("listing", d.listingId())
                .param("kind", d.kind())
                .param("m", d.merchantId())
                .param("decision", d.decision())
                .param("reasons", d.reasons().toArray(String[]::new))
                .param("flags", d.flags().toArray(String[]::new))
                .param("note", d.note())
                .param("by", d.decidedBy())
                .param("role", d.role())
                .param("at", ts(d.decidedAt()))
                .update();
    }

    @Override
    public Optional<Decision> latest(String listingId) {
        return jdbc.sql("""
                        select id, listing_id, kind, merchant_id, decision, reasons, flags, note, decided_by, role,
                               decided_at
                          from catalogue.vetting_decisions where listing_id = :l
                         order by decided_at desc, id desc limit 1""")
                .param("l", listingId)
                .query((rs, _) -> new Decision(
                        rs.getString("id"),
                        rs.getString("listing_id"),
                        rs.getString("kind"),
                        rs.getString("merchant_id"),
                        rs.getString("decision"),
                        strings(rs.getArray("reasons")),
                        strings(rs.getArray("flags")),
                        rs.getString("note"),
                        rs.getString("decided_by"),
                        rs.getString("role"),
                        requiredInstant(rs, "decided_at")))
                .optional();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
