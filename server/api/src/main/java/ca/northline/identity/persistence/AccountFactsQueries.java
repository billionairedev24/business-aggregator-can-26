package ca.northline.identity.persistence;

import ca.northline.identity.api.AccountFacts;
import ca.northline.identity.application.AccountSettings.AccountSettingsStore;
import ca.northline.shared.NotFound;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AccountFacts} over {@code identity.users}, {@code addresses} and {@code household_members}. */
@Repository
@RequiredArgsConstructor
class AccountFactsQueries implements AccountFacts {

    private final JdbcClient jdbc;
    private final AccountSettingsStore settings;
    private final Clock clock;

    @Override
    public Facts of(String userId) {
        return jdbc.sql("""
                        select u.mfa_primary,
                               (select count(*) from identity.addresses a
                                 where a.user_id = u.id and a.street is not null and a.deleted_at is null) as addresses,
                               (select count(*) from identity.household_members x
                                 where x.household_id = (select m.household_id from identity.household_members m
                                                          where m.user_id = u.id limit 1)) as members,
                               (select trim(a.province) from identity.addresses a
                                 where a.user_id = u.id and a.street is not null and a.deleted_at is null
                                 order by a.is_default desc nulls last, a.created_at desc limit 1) as province
                          from identity.users u where u.id = :u
                        """)
                .param("u", userId)
                .query((rs, _) -> new Facts(
                        rs.getString("mfa_primary"),
                        rs.getInt("addresses"),
                        rs.getInt("members"),
                        rs.getString("province")))
                .optional()
                .orElse(new Facts(null, 0, 0, null));
    }

    @Override
    public void locale(String userId, String locale) {
        settings.locale(userId, locale.startsWith("fr") ? "fr-CA" : "en-CA", clock.instant());
    }

    @Override
    public OwnProfile profile(String userId) {
        var p = settings.profile(userId).orElseThrow(() -> new NotFound("user", userId));
        var b = p.birthday();
        return new OwnProfile(
                p.firstName(),
                p.lastName(),
                p.email(),
                p.phone(),
                p.locale(),
                p.memberSince(),
                p.pronouns(),
                b == null ? null : "%02d-%02d".formatted(b.getMonthValue(), b.getDayOfMonth()));
    }
}
