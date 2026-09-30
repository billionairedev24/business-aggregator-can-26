package ca.northline.food.persistence;

import ca.northline.food.application.PosImportViews.Diff;
import ca.northline.food.application.PosMenuSource.PosMenu;
import ca.northline.food.application.PosStore;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code food.pos_connections}, {@code pos_oauth_requests}, {@code pos_links}, {@code pos_imports} (S-36). */
@Repository
@RequiredArgsConstructor
class PosJdbc implements PosStore {

    private final JdbcClient jdbc;

    private static @Nullable OffsetDateTime ts(@Nullable Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<Connection> connections(String merchantId) {
        return jdbc.sql("select * from food.pos_connections where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> connection(rs))
                .list();
    }

    @Override
    public Optional<Connection> connection(String merchantId, PosProvider provider) {
        return jdbc.sql("select * from food.pos_connections where merchant_id = :m and provider = :p")
                .param("m", merchantId)
                .param("p", provider.code())
                .query((rs, _) -> connection(rs))
                .optional();
    }

    @Override
    public Connection saveGrant(
            String id,
            String merchantId,
            PosProvider provider,
            String accountId,
            String accountLabel,
            @Nullable Sealed credentials,
            Instant at) {
        jdbc.sql("""
                        insert into food.pos_connections (merchant_id, provider, id, status, account_id, account_label,
                          token_ref, credentials_key, credentials_enc, connected_at, state_changed_at)
                        values (:m, :p, :id, 'connected', :account, :label, :ref, :key, :enc, :at, :at)
                        on conflict (merchant_id, provider) do update set status = 'connected',
                          account_id = excluded.account_id, account_label = excluded.account_label,
                          token_ref = excluded.token_ref, credentials_key = excluded.credentials_key,
                          credentials_enc = excluded.credentials_enc, connected_at = excluded.connected_at,
                          state_changed_at = excluded.state_changed_at""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("id", id)
                .param("account", accountId)
                .param("label", accountLabel)
                .param("ref", credentials == null ? null : credentials.keyRef())
                .param("key", credentials == null ? null : credentials.wrappedKey())
                .param("enc", credentials == null ? null : credentials.ciphertext())
                .param("at", ts(at))
                .update();
        return connection(merchantId, provider).orElseThrow();
    }

    @Override
    public Optional<Sealed> credentials(String connectionId) {
        return jdbc.sql("""
                        select token_ref, credentials_key, credentials_enc from food.pos_connections
                        where id = :id and credentials_enc is not null""")
                .param("id", connectionId)
                .query((rs, _) -> new Sealed(
                        rs.getString("token_ref"), rs.getBytes("credentials_key"), rs.getBytes("credentials_enc")))
                .optional();
    }

    @Override
    public void replaceCredentials(String connectionId, Sealed c) {
        jdbc.sql("""
                        update food.pos_connections set token_ref = :ref, credentials_key = :key, credentials_enc = :enc
                        where id = :id""")
                .param("id", connectionId)
                .param("ref", c.keyRef())
                .param("key", c.wrappedKey())
                .param("enc", c.ciphertext())
                .update();
    }

    @Override
    public void setStatus(String connectionId, Status status, Instant at) {
        jdbc.sql("update food.pos_connections set status = :s, state_changed_at = :at where id = :id")
                .param("id", connectionId)
                .param("s", lower(status))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void disconnect(String connectionId, Instant at) {
        jdbc.sql("""
                        update food.pos_connections set status = 'disconnected', token_ref = null,
                          credentials_key = null, credentials_enc = null, state_changed_at = :at where id = :id""").param("id", connectionId).param("at", ts(at)).update();
    }

    @Override
    public void recordImport(String connectionId, Instant at) {
        jdbc.sql("update food.pos_connections set last_import_at = :at where id = :id")
                .param("id", connectionId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void saveRequest(OAuthRequest r) {
        jdbc.sql("delete from food.pos_oauth_requests where expires_at < :now")
                .param("now", ts(r.createdAt()))
                .update();
        jdbc.sql("""
                        insert into food.pos_oauth_requests (state_hash, merchant_id, user_id, provider, menu_id,
                          created_at, expires_at) values (:h, :m, :u, :p, :menu, :created, :expires)""")
                .param("h", r.stateHash())
                .param("m", r.merchantId())
                .param("u", r.userId())
                .param("p", r.provider().code())
                .param("menu", r.menuId())
                .param("created", ts(r.createdAt()))
                .param("expires", ts(r.expiresAt()))
                .update();
    }

    @Override
    public Optional<OAuthRequest> takeRequest(String stateHash, Instant now) {
        return jdbc.sql("delete from food.pos_oauth_requests where state_hash = :h returning *")
                .param("h", stateHash)
                .query((rs, _) -> new OAuthRequest(
                        rs.getString("state_hash"),
                        rs.getString("merchant_id"),
                        rs.getString("user_id"),
                        CodedEnum.fromCode(PosProvider.class, rs.getString("provider")),
                        rs.getString("menu_id"),
                        Objects.requireNonNull(instant(rs, "created_at")),
                        Objects.requireNonNull(instant(rs, "expires_at"))))
                .optional()
                .filter(r -> r.expiresAt().isAfter(now));
    }

    @Override
    public Map<String, Link> links(String merchantId, PosProvider provider, LinkKind kind, String scope) {
        return jdbc
                .sql("""
                        select * from food.pos_links
                        where merchant_id = :m and provider = :p and kind = :k and scope = :s""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("k", lower(kind))
                .param("s", scope)
                .query((rs, _) -> new Link(
                        kind,
                        rs.getString("scope"),
                        rs.getString("external_id"),
                        rs.getString("local_id"),
                        rs.getString("content_hash"),
                        instant(rs, "removed_at")))
                .list()
                .stream()
                .collect(Collectors.toMap(Link::externalId, Function.identity()));
    }

    @Override
    public void saveLink(String merchantId, PosProvider provider, Link link, Instant at) {
        jdbc.sql("""
                        insert into food.pos_links (merchant_id, provider, kind, scope, external_id, local_id,
                          content_hash, removed_at, updated_at)
                        values (:m, :p, :k, :s, :e, :local, :hash, :removed, :at)
                        on conflict (merchant_id, provider, kind, scope, external_id) do update set
                          local_id = excluded.local_id, content_hash = excluded.content_hash,
                          removed_at = excluded.removed_at, updated_at = excluded.updated_at""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("k", lower(link.kind()))
                .param("s", link.scope())
                .param("e", link.externalId())
                .param("local", link.localId())
                .param("hash", link.contentHash())
                .param("removed", ts(link.removedAt()))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void saveImport(ImportRow r) {
        jdbc.sql("""
                        insert into food.pos_imports (id, merchant_id, menu_id, provider, status, menu, diff, created_by,
                          created_at) values (:id, :m, :menu, :p, :status, cast(:body as jsonb), cast(:diff as jsonb),
                          :by, :at)""")
                .param("id", r.id())
                .param("m", r.merchantId())
                .param("menu", r.menuId())
                .param("p", r.provider().code())
                .param("status", r.status())
                .param("body", KitchenSql.json(r.menu()))
                .param("diff", KitchenSql.json(r.diff()))
                .param("by", r.createdBy())
                .param("at", ts(r.createdAt()))
                .update();
    }

    @Override
    public Optional<ImportRow> importRow(String merchantId, String importId) {
        return jdbc.sql("select * from food.pos_imports where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", importId)
                .query((rs, _) -> new ImportRow(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("menu_id"),
                        CodedEnum.fromCode(PosProvider.class, rs.getString("provider")),
                        rs.getString("status"),
                        KitchenSql.read(rs.getString("menu"), PosMenu.class),
                        KitchenSql.read(rs.getString("diff"), Diff.class),
                        rs.getString("created_by"),
                        Objects.requireNonNull(instant(rs, "created_at")),
                        instant(rs, "applied_at")))
                .optional();
    }

    @Override
    public void markImport(String importId, String status, Instant at) {
        jdbc.sql("""
                        update food.pos_imports set status = :s,
                          applied_at = case when :s = 'applied' then :at else applied_at end where id = :id""")
                .param("id", importId)
                .param("s", status)
                .param("at", ts(at))
                .update();
    }

    private static Connection connection(ResultSet rs) throws SQLException {
        return new Connection(
                rs.getString("id"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(PosProvider.class, rs.getString("provider")),
                Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                rs.getString("account_id"),
                rs.getString("account_label"),
                instant(rs, "connected_at"),
                instant(rs, "last_import_at"));
    }
}
