package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.instant;
import static ca.northline.catalogue.persistence.Sql.intOrNull;
import static ca.northline.catalogue.persistence.Sql.strings;
import static ca.northline.catalogue.persistence.Sql.ts;

import ca.northline.catalogue.application.IntegrationRepository;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** {@code catalogue.integrations}, {@code commerce_oauth_requests} and {@code commerce_webhook_receipts} (S-35). */
@Repository
@RequiredArgsConstructor
class IntegrationAdapter implements IntegrationRepository {

    private static final TypeReference<List<SyncError>> ERRORS = new TypeReference<>() {};

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
    public Optional<Connection> byId(String id) {
        return jdbc.sql("select * from catalogue.integrations where id = :id")
                .param("id", id)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public List<Connection> byAccount(CommerceProvider provider, String externalAccountId) {
        return jdbc.sql("select * from catalogue.integrations where provider = :p and external_account_id = :a")
                .param("p", provider.code())
                .param("a", externalAccountId)
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public void saveGrant(Connection c, Sealed credentials) {
        jdbc.sql("""
                        insert into catalogue.integrations (merchant_id, provider, id, status, connection_state,
                          external_account_id, account_label, scopes, connected_at, token_ref, credentials_key,
                          credentials_enc, sync_status, webhooks, last_error, state_changed_at)
                        values (:m, :p, :id, 'connected', 'ok', :account, :label, :scopes, :at, :ref, :key, :enc,
                          'importing', 'none', null, :at)
                        on conflict (merchant_id, provider) do update set id = excluded.id, status = 'connected',
                          connection_state = 'ok', external_account_id = excluded.external_account_id,
                          account_label = excluded.account_label, scopes = excluded.scopes,
                          connected_at = excluded.connected_at, token_ref = excluded.token_ref,
                          credentials_key = excluded.credentials_key, credentials_enc = excluded.credentials_enc,
                          sync_status = 'importing', webhooks = 'none', last_error = null,
                          state_changed_at = excluded.state_changed_at
                        """)
                .param("m", c.merchantId())
                .param("p", c.provider().code())
                .param("id", c.requiredId())
                .param("account", c.externalAccountId())
                .param("label", c.accountLabel())
                .param("scopes", Sql.array(c.scopes()))
                .param("at", ts(c.connectedAt()))
                .param("ref", credentials.keyRef())
                .param("key", credentials.wrappedKey())
                .param("enc", credentials.ciphertext())
                .update();
    }

    @Override
    public Optional<Sealed> credentials(String id) {
        return jdbc.sql("""
                        select token_ref, credentials_key, credentials_enc from catalogue.integrations
                        where id = :id and credentials_enc is not null""")
                .param("id", id)
                .query((rs, _) -> new Sealed(
                        rs.getString("token_ref"), rs.getBytes("credentials_key"), rs.getBytes("credentials_enc")))
                .optional();
    }

    @Override
    public void replaceCredentials(String id, Sealed credentials) {
        jdbc.sql("""
                        update catalogue.integrations set token_ref = :ref, credentials_key = :key,
                          credentials_enc = :enc where id = :id""")
                .param("id", id)
                .param("ref", credentials.keyRef())
                .param("key", credentials.wrappedKey())
                .param("enc", credentials.ciphertext())
                .update();
    }

    @Override
    public void disconnect(String id, Instant at) {
        jdbc.sql("""
                        update catalogue.integrations set status = 'disconnected', connection_state = 'ok',
                          token_ref = null, credentials_key = null, credentials_enc = null, webhooks = 'none',
                          sync_status = null, state_changed_at = :at where id = :id""").param("id", id).param("at", ts(at)).update();
    }

    @Override
    public void markReconnect(String id, String error, Instant at) {
        jdbc.sql("""
                        update catalogue.integrations set connection_state = 'reconnect', last_error = :error,
                          sync_status = case when sync_status = 'importing' then 'failed' else sync_status end,
                          state_changed_at = :at where id = :id""")
                .param("id", id)
                .param("error", truncate(error))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void markSyncing(String id) {
        jdbc.sql("update catalogue.integrations set sync_status = 'importing' where id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public void recordSync(String id, SyncResult r) {
        jdbc.sql("""
                        update catalogue.integrations set sync_status = 'ok', last_sync_at = :at, last_polled_at = :at,
                          last_sync_count = :updated, created_count = :created, hidden_count = :hidden,
                          sync_errors = cast(:errors as jsonb), last_error = null where id = :id""")
                .param("id", id)
                .param("at", ts(r.at()))
                .param("updated", r.updated())
                .param("created", r.created())
                .param("hidden", r.hidden())
                .param("errors", Sql.JSON.writeValueAsString(r.errors()))
                .update();
    }

    @Override
    public void recordFailure(String id, String error, Instant at) {
        jdbc.sql("""
                        update catalogue.integrations set sync_status = 'failed', last_error = :error,
                          last_polled_at = :at where id = :id""")
                .param("id", id)
                .param("error", truncate(error))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void setWebhooks(String id, Webhooks state) {
        jdbc.sql("update catalogue.integrations set webhooks = :w where id = :id")
                .param("id", id)
                .param("w", state.name().toLowerCase(Locale.ROOT))
                .update();
    }

    private static final String DUE = """
            status = 'connected' and connection_state = 'ok' and id is not null
              and (last_polled_at is null
                   or last_polled_at < case when webhooks = 'active' then :reconcileBefore else :pollBefore end)""";

    @Override
    public List<String> due(Instant pollBefore, Instant reconcileBefore, int limit) {
        return jdbc.sql("select id from catalogue.integrations where " + DUE
                        + " order by last_polled_at nulls first limit :limit")
                .param("pollBefore", ts(pollBefore))
                .param("reconcileBefore", ts(reconcileBefore))
                .param("limit", limit)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString("id")))
                .list();
    }

    @Override
    public boolean claim(String id, Instant pollBefore, Instant reconcileBefore, Instant now) {
        return jdbc.sql("update catalogue.integrations set last_polled_at = :now where id = :id and " + DUE)
                        .param("id", id)
                        .param("now", ts(now))
                        .param("pollBefore", ts(pollBefore))
                        .param("reconcileBefore", ts(reconcileBefore))
                        .update()
                == 1;
    }

    // ── OAuth requests ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void saveRequest(OAuthRequest r) {
        jdbc.sql("""
                        insert into catalogue.commerce_oauth_requests (state_hash, merchant_id, user_id, provider, shop,
                          created_at, expires_at) values (:h, :m, :u, :p, :shop, :created, :expires)""")
                .param("h", r.stateHash())
                .param("m", r.merchantId())
                .param("u", r.userId())
                .param("p", r.provider().code())
                .param("shop", r.shop())
                .param("created", ts(r.createdAt()))
                .param("expires", ts(r.expiresAt()))
                .update();
    }

    @Override
    public Optional<OAuthRequest> takeRequest(String stateHash, Instant now) {
        return jdbc.sql("delete from catalogue.commerce_oauth_requests where state_hash = :h returning *")
                .param("h", stateHash)
                .query((rs, _) -> new OAuthRequest(
                        rs.getString("state_hash"),
                        rs.getString("merchant_id"),
                        rs.getString("user_id"),
                        CodedEnum.fromCode(CommerceProvider.class, rs.getString("provider")),
                        rs.getString("shop"),
                        Sql.requiredInstant(rs, "created_at"),
                        Sql.requiredInstant(rs, "expires_at")))
                .optional()
                .filter(r -> r.expiresAt().isAfter(now));
    }

    @Override
    public boolean firstDelivery(CommerceProvider provider, String deliveryId, Instant at) {
        return jdbc.sql("""
                                insert into catalogue.commerce_webhook_receipts (provider, delivery_id, received_at)
                                values (:p, :d, :at) on conflict do nothing""")
                        .param("p", provider.code())
                        .param("d", deliveryId)
                        .param("at", ts(at))
                        .update()
                == 1;
    }

    @Override
    public int purge(Instant now, Instant receiptsBefore) {
        var requests = jdbc.sql("delete from catalogue.commerce_oauth_requests where expires_at < :now")
                .param("now", ts(now))
                .update();
        var receipts = jdbc.sql("delete from catalogue.commerce_webhook_receipts where received_at < :before")
                .param("before", ts(receiptsBefore))
                .update();
        return requests + receipts;
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static Connection map(ResultSet rs) throws SQLException {
        var syncStatus = rs.getString("sync_status");
        var errors = rs.getString("sync_errors");
        return new Connection(
                rs.getString("merchant_id"),
                CodedEnum.fromCode(CommerceProvider.class, rs.getString("provider")),
                rs.getString("id"),
                "connected".equals(rs.getString("status")),
                true,
                "reconnect".equals(rs.getString("connection_state")),
                rs.getString("external_account_id"),
                rs.getString("account_label"),
                new HashSet<>(strings(rs, "scopes")),
                instant(rs, "connected_at"),
                instant(rs, "last_sync_at"),
                intOrNull(rs, "last_sync_count"),
                rs.getInt("created_count"),
                rs.getInt("hidden_count"),
                errors == null ? List.of() : Sql.JSON.readValue(errors, ERRORS),
                syncStatus == null ? null : SyncStatus.valueOf(syncStatus.toUpperCase(Locale.ROOT)),
                Webhooks.valueOf(rs.getString("webhooks").toUpperCase(Locale.ROOT)),
                instant(rs, "last_polled_at"),
                rs.getString("last_error"));
    }
}
