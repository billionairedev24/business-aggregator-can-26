package ca.northline.identity.persistence;

import ca.northline.shared.security.PlatformRoles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PlatformRoles} over {@code identity.platform_roles} (written by northline-auth's admins; S-90). */
@Repository
@RequiredArgsConstructor
class PlatformRoleQueries implements PlatformRoles {

    private final JdbcClient jdbc;

    @Override
    public List<String> of(String userId) {
        return jdbc.sql("select role from identity.platform_roles where user_id = :id order by role")
                .param("id", userId)
                .query((rs, _) -> rs.getString("role"))
                .list();
    }
}
