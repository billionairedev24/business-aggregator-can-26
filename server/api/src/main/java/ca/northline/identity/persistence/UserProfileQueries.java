package ca.northline.identity.persistence;

import ca.northline.identity.application.UserProfiles;
import ca.northline.identity.domain.UserProfile;
import ca.northline.region.api.Regions;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code identity.users} → {@link UserProfile}. Member-since is the account's creation date in the platform zone (an
 * account belongs to no market; region configuration).
 */
@Repository
@RequiredArgsConstructor
class UserProfileQueries implements UserProfiles {

    private final JdbcClient jdbc;
    private final Regions regions;

    @Override
    public Optional<UserProfile> find(String userId) {
        return jdbc.sql("""
                        select id, first_name, last_name, display_name, email::text as email, phone, locale, mfa_primary,
                               created_at
                          from identity.users
                         where id = :id and coalesce(status, 'active') <> 'erased'
                        """)
                .param("id", userId)
                .query((rs, _) -> UserProfile.of(
                        rs.getString("id"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("locale"),
                        rs.getObject("created_at", OffsetDateTime.class)
                                .atZoneSameInstant(regions.platformZone())
                                .toLocalDate(),
                        rs.getString("mfa_primary")))
                .optional();
    }
}
