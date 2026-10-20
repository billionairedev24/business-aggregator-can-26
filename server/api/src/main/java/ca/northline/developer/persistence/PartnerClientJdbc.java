package ca.northline.developer.persistence;

import ca.northline.developer.api.PartnerClients.Binding;
import ca.northline.developer.application.PartnerClientStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code developer.partner_clients} + {@code developer.partner_bindings} (S-158). */
@Repository
@RequiredArgsConstructor
class PartnerClientJdbc implements PartnerClientStore {

    private static final String COLUMNS = """
            p.id, p.name, p.display_name, p.jwk_set_url, p.scopes, p.token_ttl_seconds, p.state, p.key_version,
            p.rotated_at, p.created_at""";

    private final JdbcClient jdbc;

    @Override
    public List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from developer.partner_clients p order by p.created_at desc, p.id")
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public Optional<Row> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from developer.partner_clients p where p.id = :id")
                .param("id", id)
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public boolean nameTaken(String name) {
        return jdbc.sql("select exists (select 1 from developer.partner_clients where name = :n)")
                .param("n", name)
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insert(Row r, String createdBy) {
        jdbc.sql("""
                        insert into developer.partner_clients (id, name, display_name, jwk_set_url, scopes,
                          token_ttl_seconds, state, key_version, created_by, created_at, updated_at)
                        values (:id, :name, :display, :url, :scopes, :ttl, 'active', 1, :by, :at, :at)""")
                .param("id", r.id())
                .param("name", r.name())
                .param("display", r.displayName())
                .param("url", r.jwkSetUrl())
                .param("scopes", r.scopes().toArray(String[]::new))
                .param("ttl", r.tokenTtlSeconds())
                .param("by", createdBy)
                .param("at", ts(r.createdAt()))
                .update();
    }

    @Override
    public void rotate(String id, String jwkSetUrl, Instant at) {
        jdbc.sql("""
                        update developer.partner_clients set jwk_set_url = :url, key_version = key_version + 1,
                          rotated_at = :at, updated_at = :at where id = :id""")
                .param("id", id)
                .param("url", jwkSetUrl)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void setState(String id, String state, @Nullable String by, Instant at) {
        var disabled = "disabled".equals(state);
        jdbc.sql("""
                        update developer.partner_clients set state = :state, disabled_by = :by, disabled_at = :dat,
                          updated_at = :at where id = :id""")
                .param("id", id)
                .param("state", state)
                .param("by", disabled ? by : null)
                .param("dat", disabled ? ts(at) : null)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<Binding> bindings(String partnerId) {
        return jdbc.sql("""
                        select merchant_id, state, requested_at, decided_at from developer.partner_bindings
                        where partner_id = :p order by requested_at, merchant_id""").param("p", partnerId).query((rs, _) -> binding(rs)).list();
    }

    @Override
    public boolean request(String partnerId, String merchantId, String staffId, Instant at) {
        return jdbc.sql("""
                                insert into developer.partner_bindings (partner_id, merchant_id, state, requested_by,
                                  requested_at) values (:p, :m, 'requested', :by, :at) on conflict do nothing""")
                        .param("p", partnerId)
                        .param("m", merchantId)
                        .param("by", staffId)
                        .param("at", ts(at))
                        .update()
                == 1;
    }

    @Override
    public boolean remove(String partnerId, String merchantId) {
        return jdbc.sql("delete from developer.partner_bindings where partner_id = :p and merchant_id = :m")
                        .param("p", partnerId)
                        .param("m", merchantId)
                        .update()
                == 1;
    }

    @Override
    public Optional<Binding> binding(String partnerId, String merchantId) {
        return jdbc.sql("""
                        select merchant_id, state, requested_at, decided_at from developer.partner_bindings
                        where partner_id = :p and merchant_id = :m""")
                .param("p", partnerId)
                .param("m", merchantId)
                .query((rs, _) -> binding(rs))
                .optional();
    }

    @Override
    public Optional<Binding> lockBinding(String partnerId, String merchantId) {
        return jdbc.sql("""
                        select merchant_id, state, requested_at, decided_at from developer.partner_bindings
                        where partner_id = :p and merchant_id = :m for update""")
                .param("p", partnerId)
                .param("m", merchantId)
                .query((rs, _) -> binding(rs))
                .optional();
    }

    @Override
    public void decide(String partnerId, String merchantId, String state, String by, Instant at) {
        jdbc.sql("""
                        update developer.partner_bindings set state = :state, decided_by = :by, decided_at = :at
                        where partner_id = :p and merchant_id = :m""")
                .param("p", partnerId)
                .param("m", merchantId)
                .param("state", state)
                .param("by", by)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<BusinessBinding> ofBusiness(String merchantId) {
        return jdbc.sql("select " + COLUMNS + """
                        , b.merchant_id, b.state as binding_state, b.requested_at, b.decided_at
                        from developer.partner_bindings b join developer.partner_clients p on p.id = b.partner_id
                        where b.merchant_id = :m order by b.requested_at desc, p.id""")
                .param("m", merchantId)
                .query((rs, _) -> new BusinessBinding(
                        row(rs),
                        new Binding(
                                Objects.requireNonNull(rs.getString("merchant_id")),
                                Objects.requireNonNull(rs.getString("binding_state")),
                                required(rs, "requested_at"),
                                instant(rs, "decided_at"))))
                .list();
    }

    private static Row row(ResultSet rs) throws SQLException {
        var scopes = rs.getArray("scopes");
        return new Row(
                Objects.requireNonNull(rs.getString("id")),
                Objects.requireNonNull(rs.getString("name")),
                Objects.requireNonNull(rs.getString("display_name")),
                Objects.requireNonNull(rs.getString("jwk_set_url")),
                scopes == null
                        ? List.of()
                        : Arrays.stream((Object[]) scopes.getArray())
                                .map(String::valueOf)
                                .toList(),
                rs.getInt("token_ttl_seconds"),
                Objects.requireNonNull(rs.getString("state")),
                rs.getInt("key_version"),
                instant(rs, "rotated_at"),
                required(rs, "created_at"));
    }

    private static Binding binding(ResultSet rs) throws SQLException {
        return new Binding(
                Objects.requireNonNull(rs.getString("merchant_id")),
                Objects.requireNonNull(rs.getString("state")),
                required(rs, "requested_at"),
                instant(rs, "decided_at"));
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Instant required(ResultSet rs, String column) throws SQLException {
        return Objects.requireNonNull(instant(rs, column), column);
    }
}
