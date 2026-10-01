package ca.northline.identity.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.identity.application.AccountSettings.AccountSettingsStore;
import ca.northline.identity.application.AccountSettings.Address;
import ca.northline.identity.application.AccountSettings.AddressChange;
import ca.northline.identity.application.AccountSettings.Household;
import ca.northline.identity.application.AccountSettings.Member;
import ca.northline.identity.application.AccountSettings.NewAddress;
import ca.northline.identity.application.AccountSettings.Profile;
import ca.northline.identity.application.AccountSettings.ProfileChange;
import ca.northline.identity.domain.UserProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AccountSettingsStore} over {@code identity.users}, {@code addresses} (removed ones keep their row with
 * {@code deleted_at}, V162 — orders may point at them) and {@code households} / {@code household_members}.
 */
@Repository
@RequiredArgsConstructor
class AccountSettingsJdbc implements AccountSettingsStore {

    private static final String ADDRESS = """
            select id, label, street, unit, city, province, postal, access_note, coalesce(is_default, false) as is_default
              from identity.addresses
             where user_id = :u and street is not null and deleted_at is null
            """;

    private final JdbcClient jdbc;
    private final Regions regions;
    private final Clock clock;

    @Override
    public Optional<Profile> profile(String userId) {
        return jdbc.sql("""
                        select id, first_name, last_name, display_name, email::text as email, phone, locale, mfa_primary,
                               created_at, pronouns, birthday_month, birthday_day, reliability_score, erasure_requested_at
                          from identity.users
                         where id = :id and coalesce(status, 'active') <> 'erased'
                        """)
                .param("id", userId)
                .query((rs, _) -> {
                    var p = UserProfile.of(
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
                            rs.getString("mfa_primary"));
                    var month = rs.getObject("birthday_month", Integer.class);
                    var day = rs.getObject("birthday_day", Integer.class);
                    return new Profile(
                            p.id(),
                            p.firstName(),
                            p.lastName(),
                            p.email(),
                            p.phone(),
                            p.locale(),
                            p.memberSince(),
                            rs.getString("pronouns"),
                            month == null || day == null ? null : MonthDay.of(month, day),
                            rs.getBigDecimal("reliability_score"),
                            instant(rs, "erasure_requested_at"));
                })
                .optional();
    }

    @Override
    public boolean emailFree(String userId, String email) {
        return !Boolean.TRUE.equals(
                jdbc.sql("select exists (select 1 from identity.users where email = cast(:e as citext) and id <> :u)")
                        .param("e", email)
                        .param("u", userId)
                        .query(Boolean.class)
                        .single());
    }

    @Override
    public void updateProfile(String userId, ProfileChange c, Instant at) {
        var birthday = c.birthday();
        jdbc.sql("""
                        update identity.users
                           set first_name = :first, last_name = :last, display_name = :display, email = cast(:email as citext),
                               pronouns = :pronouns, birthday_month = :month, birthday_day = :day, updated_at = :at
                         where id = :u
                        """)
                .param("first", c.firstName())
                .param("last", c.lastName())
                .param("display", (c.firstName() + " " + c.lastName()).strip())
                .param("email", c.email())
                .param("pronouns", c.pronouns())
                .param("month", birthday == null ? null : birthday.getMonthValue())
                .param("day", birthday == null ? null : birthday.getDayOfMonth())
                .param("at", ts(at))
                .param("u", userId)
                .update();
    }

    @Override
    public void locale(String userId, String locale, Instant at) {
        jdbc.sql("update identity.users set locale = :l, updated_at = :at where id = :u")
                .param("l", locale)
                .param("at", ts(at))
                .param("u", userId)
                .update();
    }

    @Override
    public void erasureRequested(String userId, Instant at) {
        jdbc.sql("update identity.users set erasure_requested_at = :at where id = :u and erasure_requested_at is null")
                .param("at", ts(at))
                .param("u", userId)
                .update();
    }

    @Override
    public List<Address> addresses(String userId) {
        return jdbc.sql(ADDRESS + " order by is_default desc nulls last, created_at desc, id desc")
                .param("u", userId)
                .query((rs, _) -> address(rs))
                .list();
    }

    @Override
    public Address insertAddress(String userId, NewAddress a, boolean isDefault) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into identity.addresses (id, user_id, label, street, unit, city, province, postal, access_note,
                               is_default, created_at)
                        values (:id, :u, :label, :street, :unit, :city, :province, :postal, :note, :def, :at)
                        """)
                .param("id", id)
                .param("u", userId)
                .param("label", a.label())
                .param("street", a.street())
                .param("unit", a.unit())
                .param("city", a.city())
                .param("province", a.province())
                .param("postal", a.postal())
                .param("note", a.note())
                .param("def", isDefault)
                .param("at", ts(clock.instant()))
                .update();
        return addresses(userId).stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void changeAddress(String userId, String addressId, AddressChange c) {
        jdbc.sql("""
                        update identity.addresses set label = :label, unit = :unit, access_note = :note
                         where id = :id and user_id = :u and deleted_at is null
                        """)
                .param("label", c.label())
                .param("unit", c.unit())
                .param("note", c.note())
                .param("id", addressId)
                .param("u", userId)
                .update();
    }

    @Override
    public void makeDefault(String userId, String addressId) {
        jdbc.sql("""
                        update identity.addresses set is_default = (id = :id)
                         where user_id = :u and deleted_at is null
                        """).param("id", addressId).param("u", userId).update();
    }

    @Override
    public void removeAddress(String userId, String addressId, Instant at) {
        jdbc.sql("""
                        update identity.addresses set deleted_at = :at, is_default = false
                         where id = :id and user_id = :u and deleted_at is null
                        """)
                .param("at", ts(at))
                .param("id", addressId)
                .param("u", userId)
                .update();
    }

    @Override
    public Household household(String userId) {
        var now = ts(clock.instant());
        var head = jdbc.sql("""
                        select h.id,
                               case when h.plus_plan in ('monthly', 'annual')
                                     and (h.renews_at is null or h.renews_at > :now) then h.plus_plan else 'none' end as plan,
                               h.plus_since, h.renews_at
                          from identity.household_members m join identity.households h on h.id = m.household_id
                         where m.user_id = :u
                         order by (h.plus_plan in ('monthly', 'annual')) desc nulls last, h.id
                         limit 1
                        """)
                .param("u", userId)
                .param("now", now)
                .query((rs, _) -> new Head(
                        rs.getString("id"), rs.getString("plan"), instant(rs, "plus_since"), instant(rs, "renews_at")))
                .optional();
        if (head.isEmpty()) {
            return new Household(null, List.of(), "none", null, null);
        }
        var h = head.get();
        var householdId = h.id();
        var plan = h.plan();
        var members = jdbc.sql("""
                        select m.user_id, coalesce(nullif(trim(coalesce(u.first_name, '') || ' ' || coalesce(u.last_name, '')), ''),
                                                   u.display_name, '') as name, coalesce(m.role, 'member') as role
                          from identity.household_members m join identity.users u on u.id = m.user_id
                         where m.household_id = :h
                         order by (m.role = 'owner') desc, name
                        """)
                .param("h", householdId)
                .query((rs, _) -> new Member(
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getString("role"),
                        userId.equals(rs.getString("user_id"))))
                .list();
        var active = !"none".equals(plan);
        return new Household(householdId, members, plan, active ? h.since() : null, active ? h.renewsAt() : null);
    }

    private record Head(
            String id,
            String plan,
            @Nullable Instant since,
            @Nullable Instant renewsAt) {}

    @Override
    public String ensureHousehold(String userId) {
        var existing = jdbc.sql("select household_id from identity.household_members where user_id = :u limit 1")
                .param("u", userId)
                .query(String.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = Ids.next();
        jdbc.sql("insert into identity.households (id, name, plus_plan) values (:id, 'Home', 'none')")
                .param("id", id)
                .update();
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (:h, :u, 'owner')")
                .param("h", id)
                .param("u", userId)
                .update();
        return id;
    }

    @Override
    public void plus(String householdId, String plan, @Nullable Instant since, @Nullable Instant renewsAt) {
        jdbc.sql(
                        "update identity.households set plus_plan = :plan, plus_since = :since, renews_at = :renews where id = :h")
                .param("plan", plan)
                .param("since", ts(since))
                .param("renews", ts(renewsAt))
                .param("h", householdId)
                .update();
    }

    private static Address address(ResultSet rs) throws SQLException {
        return new Address(
                rs.getString("id"),
                rs.getString("label"),
                rs.getString("street"),
                rs.getString("unit"),
                rs.getString("city"),
                rs.getString("province").strip(),
                rs.getString("postal"),
                rs.getString("access_note"),
                rs.getBoolean("is_default"));
    }
}
