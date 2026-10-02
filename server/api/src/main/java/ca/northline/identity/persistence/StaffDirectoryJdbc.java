package ca.northline.identity.persistence;

import ca.northline.identity.api.StaffDirectory;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.StaffRole;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link StaffDirectory} over {@code identity.users} and {@code identity.platform_roles}. */
@Repository
@RequiredArgsConstructor
class StaffDirectoryJdbc implements StaffDirectory {

    private static final String MEMBER = """
            select u.id, u.display_name, u.email,
                   coalesce((select array_agg(r.role order by r.role) from identity.platform_roles r where r.user_id = u.id),
                            '{}') as roles,
                   (select min(r.granted_at) from identity.platform_roles r where r.user_id = u.id) as granted_at
              from identity.users u""";

    private final JdbcClient jdbc;

    @Override
    public List<Member> members() {
        return jdbc.sql(MEMBER
                        + " where exists (select 1 from identity.platform_roles r where r.user_id = u.id)"
                        + " order by u.display_name, u.id")
                .query((rs, _) -> member(rs))
                .list();
    }

    @Override
    public Optional<Member> member(String userId) {
        return jdbc.sql(MEMBER + " where u.id = :id")
                .param("id", userId)
                .query((rs, _) -> member(rs))
                .optional();
    }

    @Override
    public Optional<Member> byEmail(String email) {
        return jdbc.sql(MEMBER
                        + " where lower(u.email::text) = lower(:email) and u.status = 'active' order by u.id limit 1")
                .param("email", email.strip())
                .query((rs, _) -> member(rs))
                .optional();
    }

    @Override
    public boolean grant(String userId, String role, String grantedBy) {
        jdbc.sql("""
                        insert into identity.platform_roles (user_id, role, granted_by) values (:u, 'staff', :by)
                        on conflict (user_id, role) do nothing""").param("u", userId).param("by", grantedBy).update();
        return jdbc.sql("""
                                insert into identity.platform_roles (user_id, role, granted_by) values (:u, :role, :by)
                                on conflict (user_id, role) do nothing""")
                        .param("u", userId)
                        .param("role", role)
                        .param("by", grantedBy)
                        .update()
                > 0;
    }

    @Override
    public boolean revoke(String userId, String role) {
        var removed = jdbc.sql("delete from identity.platform_roles where user_id = :u and role = :role")
                        .param("u", userId)
                        .param("role", role)
                        .update()
                > 0;
        jdbc.sql("""
                        delete from identity.platform_roles r where r.user_id = :u and r.role = :staff
                           and not exists (select 1 from identity.platform_roles o where o.user_id = :u and o.role <> :staff)""").param("u", userId).param("staff", StaffRole.STAFF).update();
        return removed;
    }

    private static Member member(ResultSet rs) throws SQLException {
        Array roles = rs.getArray("roles");
        return new Member(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("email"),
                roles == null ? List.of() : Arrays.asList((String[]) roles.getArray()),
                JdbcTimes.instant(rs, "granted_at"));
    }
}
