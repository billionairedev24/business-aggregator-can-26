package ca.northline.messaging.persistence;

import ca.northline.messaging.application.Consents.ConsentStore;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentRecord;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ConsentStore}: {@code messaging.consent_records} (V300), append-only. */
@Repository
@RequiredArgsConstructor
class ConsentRecordsJdbc implements ConsentStore {

    static final String COLUMNS = """
            id, user_id, category, action, at, source, wording_version, language, address_hash, ip_prefix,
            user_agent_hash, actor_id""";

    private final JdbcClient jdbc;

    @Override
    public List<ConsentRecord> history(String userId) {
        return jdbc.sql("select " + COLUMNS + """
                         from messaging.consent_records where user_id = :u order by at desc, id desc
                        """)
                .param("u", userId)
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public Optional<ConsentRecord> latest(String userId, ConsentCategory category) {
        return jdbc.sql("select " + COLUMNS + """
                         from messaging.consent_records where user_id = :u and category = :c
                         order by at desc, id desc limit 1
                        """)
                .param("u", userId)
                .param("c", category.code())
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public void append(ConsentRecord r) {
        jdbc.sql("insert into messaging.consent_records (" + COLUMNS + """
                        ) values (:id, :u, :c, :action, :at, :source, :wording, :lang, :address, :ip, :ua, :actor)
                        """)
                .param("id", r.id())
                .param("u", r.userId())
                .param("c", r.category().code())
                .param("action", r.granted() ? "granted" : "withdrawn")
                .param("at", Timestamp.from(r.at()))
                .param("source", r.source().code())
                .param("wording", r.wordingVersion())
                .param("lang", r.language())
                .param("address", r.addressHash())
                .param("ip", r.ipPrefix())
                .param("ua", r.userAgentHash())
                .param("actor", r.actorId())
                .update();
    }

    @Override
    public List<ConsentRecord> byAddressHash(String addressHash) {
        return jdbc.sql("select " + COLUMNS + """
                         from messaging.consent_records where address_hash = :h order by at desc, id desc
                        """)
                .param("h", addressHash)
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public void minimise(String userId) {
        jdbc.sql("""
                        update messaging.consent_records set ip_prefix = null, user_agent_hash = null
                         where user_id = :u and (ip_prefix is not null or user_agent_hash is not null)
                        """).param("u", userId).update();
    }

    @Override
    public int purgeWithdrawnBefore(Instant before) {
        return jdbc.sql("""
                        with latest as (
                          select distinct on (user_id, category) user_id, category, action, at
                            from messaging.consent_records
                           order by user_id, category, at desc, id desc)
                        delete from messaging.consent_records r
                         using latest l
                         where r.user_id = l.user_id and r.category = l.category
                           and l.action = 'withdrawn' and l.at < :before
                        """).param("before", Timestamp.from(before)).update();
    }

    static ConsentRecord row(ResultSet rs) throws SQLException {
        return new ConsentRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                CodedEnum.fromCode(ConsentCategory.class, rs.getString("category")),
                "granted".equals(rs.getString("action")),
                rs.getTimestamp("at").toInstant(),
                CodedEnum.fromCode(ConsentSource.class, rs.getString("source")),
                rs.getString("wording_version"),
                rs.getString("language"),
                rs.getString("address_hash"),
                rs.getString("ip_prefix"),
                rs.getString("user_agent_hash"),
                rs.getString("actor_id"));
    }
}
