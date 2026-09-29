package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.instant;
import static ca.northline.catalogue.persistence.Sql.intOrNull;
import static ca.northline.catalogue.persistence.Sql.ts;

import ca.northline.catalogue.application.IntegrationRepository;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.integrations}. */
@Repository
@RequiredArgsConstructor
class IntegrationAdapter implements IntegrationRepository {

    private final JdbcClient jdbc;

    @Override
    public List<Connection> all(String merchantId) {
        return jdbc.sql("select * from catalogue.integrations where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public Optional<Connection> find(String merchantId, CommerceProvider provider) {
        return jdbc.sql("select * from catalogue.integrations where merchant_id = :m and provider = :p")
                .param("m", merchantId)
                .param("p", provider.code())
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public void upsert(String merchantId, Connection c) {
        jdbc.sql("""
                        insert into catalogue.integrations (merchant_id, provider, status, account_label, connected_at,
                          last_sync_at, last_sync_count)
                        values (:m, :p, :status, :account, :connectedAt, :syncAt, :syncCount)
                        on conflict (merchant_id, provider) do update set status = excluded.status,
                          account_label = excluded.account_label, connected_at = excluded.connected_at,
                          last_sync_at = excluded.last_sync_at, last_sync_count = excluded.last_sync_count
                        """)
                .param("m", merchantId)
                .param("p", c.provider().code())
                .param("status", c.connected() ? "connected" : "disconnected")
                .param("account", c.accountLabel())
                .param("connectedAt", ts(c.connectedAt()))
                .param("syncAt", ts(c.lastSyncAt()))
                .param("syncCount", c.lastSyncCount())
                .update();
    }

    private static Connection map(ResultSet rs) throws SQLException {
        return new Connection(
                CodedEnum.fromCode(CommerceProvider.class, rs.getString("provider")),
                "connected".equals(rs.getString("status")),
                rs.getString("account_label"),
                instant(rs, "connected_at"),
                instant(rs, "last_sync_at"),
                intOrNull(rs, "last_sync_count"));
    }
}
