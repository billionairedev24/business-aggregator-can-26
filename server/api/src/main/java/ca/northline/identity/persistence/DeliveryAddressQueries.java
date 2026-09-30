package ca.northline.identity.persistence;

import ca.northline.identity.api.DeliveryAddresses;
import ca.northline.identity.api.SecondFactors;
import ca.northline.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeliveryAddresses} over {@code identity.addresses} and {@link SecondFactors} over {@code identity.users.mfa_primary}
 * (northline-auth keeps it current: passkey | totp, or sms for none) (S-51). Addresses aren't geocoded here: {@code geom} and {@code zone_id} stay empty until
 * the Location screen (S-47) resolves places.
 */
@Repository
@RequiredArgsConstructor
class DeliveryAddressQueries implements DeliveryAddresses, SecondFactors {

    private static final String COLUMNS = """
            select id, street, unit, city, province, postal, access_note, coalesce(is_default, false) as is_default
              from identity.addresses
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Address> of(String userId) {
        return jdbc.sql(COLUMNS
                        + " where user_id = :u and street is not null order by is_default desc nulls last, id desc")
                .param("u", userId)
                .query((rs, _) -> address(rs))
                .list();
    }

    @Override
    public Optional<Address> find(String userId, String addressId) {
        return jdbc.sql(COLUMNS + " where user_id = :u and id = :id and street is not null")
                .param("u", userId)
                .param("id", addressId)
                .query((rs, _) -> address(rs))
                .optional();
    }

    @Override
    @Transactional
    public Address save(String userId, NewAddress a) {
        var existing = jdbc.sql(COLUMNS + """
                         where user_id = :u and lower(street) = lower(:street)
                           and coalesce(lower(unit), '') = coalesce(lower(:unit), '')
                           and replace(upper(postal), ' ', '') = replace(upper(:postal), ' ', '')
                        """)
                .param("u", userId)
                .param("street", a.street())
                .param("unit", a.unit())
                .param("postal", a.postal())
                .query((rs, _) -> address(rs))
                .optional();
        if (existing.isPresent()) {
            jdbc.sql(
                            "update identity.addresses set access_note = :note, city = :city, province = :province where id = :id")
                    .param("note", a.note())
                    .param("city", a.city())
                    .param("province", a.province())
                    .param("id", existing.get().id())
                    .update();
            return find(userId, existing.get().id()).orElseThrow();
        }
        var id = Ids.next();
        var first = of(userId).isEmpty();
        jdbc.sql("""
                        insert into identity.addresses (id, user_id, street, unit, city, province, postal, access_note, is_default)
                        values (:id, :u, :street, :unit, :city, :province, :postal, :note, :def)
                        """)
                .param("id", id)
                .param("u", userId)
                .param("street", a.street())
                .param("unit", a.unit())
                .param("city", a.city())
                .param("province", a.province())
                .param("postal", a.postal())
                .param("note", a.note())
                .param("def", first)
                .update();
        return find(userId, id).orElseThrow();
    }

    @Override
    public boolean hasSecondFactor(String userId) {
        return Boolean.TRUE.equals(
                jdbc.sql("""
                        select exists (select 1 from identity.users where id = :u and mfa_primary in ('passkey', 'totp'))
                        """).param("u", userId).query(Boolean.class).single());
    }

    private static Address address(ResultSet rs) throws SQLException {
        return new Address(
                rs.getString("id"),
                rs.getString("street"),
                rs.getString("unit"),
                rs.getString("city"),
                rs.getString("province").strip(),
                rs.getString("postal"),
                rs.getString("access_note"),
                rs.getBoolean("is_default"));
    }
}
