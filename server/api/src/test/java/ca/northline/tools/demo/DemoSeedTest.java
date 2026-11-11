package ca.northline.tools.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.platform.AesKeyRing;
import ca.northline.tools.CategorySeeder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S-194: the demo seed against a database with the production migrations only (what a fresh demo server has) — every
 * category the consumer site links to gets a live, verified business with a priced, photographed listing; the market
 * goes live; the accounts sign in with the credentials handed in; a re-run adds nothing; a reset removes every demo
 * row and V016's triggers are never bypassed (any violation would fail the seed's transaction).
 */
class DemoSeedTest {

    private static final String TOTP_KEY = "bm9ydGhsaW5lLWxvY2FsLWRldi10b3RwLWtleS0zMmI=";
    private static final Path REPO = Path.of("").toAbsolutePath().getParent().getParent();

    private static PostgreSQLContainer pg;

    @TempDir
    static Path media;

    @BeforeAll
    static void database() {
        pg = new PostgreSQLContainer(
                        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("northline")
                .withUsername("northline")
                .withPassword("northline")
                .withCommand("postgres", "-c", "fsync=off");
        pg.start();
        var ds = dataSource();
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        new CategorySeeder(ds).seed();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void refusedWithoutTheFlagAndUnderStagingOrProd() {
        assertThat(DemoGuard.refusal(Map.of(), Map.of())).contains("NORTHLINE_DEMO_SEED=true");
        assertThat(DemoGuard.refusal(Map.of(), Map.of("NORTHLINE_DEMO_SEED", "true", "SPRING_PROFILES_ACTIVE", "dev")))
                .isNull();
        assertThat(DemoGuard.refusal(
                        Map.of(), Map.of("NORTHLINE_DEMO_SEED", "true", "NORTHLINE_ENVIRONMENT", "dryrun")))
                .isNull();
        for (var env : List.of(
                Map.of("NORTHLINE_DEMO_SEED", "true", "SPRING_PROFILES_ACTIVE", "prod"),
                Map.of("NORTHLINE_DEMO_SEED", "true", "SPRING_PROFILES_ACTIVE", "dev,staging"),
                Map.of("NORTHLINE_DEMO_SEED", "true", "NORTHLINE_ENVIRONMENT", "production"))) {
            assertThat(DemoGuard.refusal(Map.of(), env)).as(env.toString()).contains("never runs under");
        }
        assertThat(DemoGuard.refusal(Map.of("spring.profiles.active", "prod"), Map.of("NORTHLINE_DEMO_SEED", "true")))
                .contains("[prod]");
    }

    @Test
    void theScriptKnowsThePlansAccounts() throws Exception {
        var script = Files.readString(REPO.resolve("scripts/demo/seed-demo.sh"));
        var line = script.lines()
                .filter(l -> l.startsWith("ACCOUNTS="))
                .findFirst()
                .orElseThrow();
        var listed = Set.of(
                line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')).split(" "));
        assertThat(listed).isEqualTo(DemoPlan.load().accounts().keySet());
    }

    @Test
    void idsAreStableAndUlidShaped() {
        assertThat(DemoIds.of("merchant:bakery"))
                .isEqualTo(DemoIds.of("merchant:bakery"))
                .hasSize(26)
                .startsWith("01JDEM");
        assertThat(DemoIds.of("merchant:bakery"))
                .isNotEqualTo(DemoIds.of("merchant:grocer"))
                .matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void seedsEveryCategoryIdempotentlyAndResets() throws Exception {
        var env = env();
        var first = DemoSeed.run(false, env, Instant.now());
        assertThat(first).contains("Categories with at least one live, verified business: **162**");

        try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "northline", "northline")) {
            var db = new Db(c);
            // every leaf category: an active merchant with a live, approved listing (or, for food, a live menu)
            var uncovered = db.list("""
                    select c.id from catalogue.categories c
                     where c.parent_id is not null
                       and not exists (
                         select 1 from merchants.merchant_categories mc join merchants.merchants m on m.id = mc.merchant_id
                          where mc.category_id = c.id and m.status = 'active' and m.id like '01JDEM%'
                            and (exists (select 1 from catalogue.services s where s.merchant_id = m.id and s.category_id = c.id
                                           and s.status = 'live' and s.vetting = 'approved')
                              or exists (select 1 from catalogue.offers o join catalogue.catalog_products p on p.id = o.product_id
                                          where o.merchant_id = m.id and p.category_id = c.id and o.status = 'live')
                              or exists (select 1 from food.menus mn where mn.merchant_id = m.id and mn.status = 'live')))
                    """, rs -> rs.getString(1));
            assertThat(uncovered).isEmpty();
            assertThat(db.count("select count(*) from region.regions where kind = 'market' and stage = 'live'"))
                    .isEqualTo(1);
            assertThat(db.count("select count(*) from region.zones")).isPositive();
            assertThat(db.count("select count(*) from catalogue.media where id like '01JDEM%'"))
                    .isGreaterThan(200);
            assertThat(db.count(
                            "select count(*) from merchants.merchants where status = 'pending' and id like '01JDEM%'"))
                    .isEqualTo(1);
            assertThat(db.count("select count(*) from payments.disputes where state = 'agent'"))
                    .isEqualTo(1);
            assertThat(
                            db.count(
                                    "select count(*) from catalogue.offers where vetting = 'pending' and vetting_flags <> '{}'"))
                    .isEqualTo(1);

            // the accounts open with what was handed in, sealed and hashed as northline-auth does
            var admin = DemoAccounts.userId("admin");
            var hash = db.text("select hash from auth.passwords where user_id = ?", admin)
                    .orElseThrow();
            assertThat(new Argon2PasswordEncoder(16, 32, 1, 19456, 2)
                            .matches(env.get("DEMO_ACCOUNT_ADMIN_PASSWORD"), hash.substring(8)))
                    .isTrue();
            var sealed = db.list(
                            "select secret_enc, key_id from auth.totp_secrets where user_id = ?",
                            rs -> new Object[] {rs.getBytes(1), rs.getString(2)},
                            admin)
                    .getFirst();
            assertThat(AesKeyRing.of("TOTP_KEY", "v1", TOTP_KEY, null)
                            .decrypt((byte[]) sealed[0], (String) sealed[1])
                            .plain())
                    .isEqualTo(env.get("DEMO_ACCOUNT_ADMIN_TOTP"));
            assertThat(db.count("select count(*) from identity.platform_roles where user_id = ?", admin))
                    .isEqualTo(10);

            var before = counts(db);
            DemoSeed.run(false, env, Instant.now());
            assertThat(counts(db)).isEqualTo(before);

            var reset = DemoSeed.run(true, env, Instant.now());
            assertThat(reset).contains("Reset first:");
            assertThat(counts(db)).isEqualTo(before);
            // a reset alone (rolled back here) leaves no demo row behind
            c.setAutoCommit(false);
            new DemoReset(db).run();
            assertThat(db.count("select count(*) from merchants.merchants where id like '01JDEM%'"))
                    .isZero();
            assertThat(db.count("select count(*) from identity.users where id like '01JDEM%'"))
                    .isZero();
            assertThat(db.count("select count(*) from payments.escrows where id like '01JDEM%'"))
                    .isZero();
            c.rollback();
        }
    }

    @Test
    void missingCredentialsStopTheSeedBeforeItWrites() {
        var env = env();
        env.remove("DEMO_ACCOUNT_OWNER_TOTP");
        assertThatThrownBy(() -> DemoSeed.run(false, env, Instant.now())).hasMessageContaining("[owner]");
    }

    private static Map<String, Long> counts(Db db) {
        return List.of(
                        "merchants.merchants",
                        "catalogue.offers",
                        "catalogue.services",
                        "food.menu_items",
                        "trust.reviews",
                        "orders.orders",
                        "booking.bookings",
                        "payments.escrows",
                        "identity.users",
                        "catalogue.media")
                .stream()
                .collect(Collectors.toMap(t -> t, t -> db.count("select count(*) from " + t)));
    }

    private static Map<String, String> env() {
        var env = new HashMap<String, String>();
        env.put("DB_URL", pg.getJdbcUrl());
        env.put("DB_USER", "northline");
        env.put("DB_PASSWORD", "northline");
        env.put("TOTP_KEY", TOTP_KEY);
        env.put("DEMO_PHOTOS_DIR", REPO.resolve("web/packages/media/source").toString());
        env.put("NORTHLINE_MEDIA_LOCALDIR", media.toString());
        for (var k : DemoPlan.load().accounts().keySet()) {
            env.put(DemoAccounts.variable(k, "PASSWORD"), "test-" + k + "-password-0123");
            env.put(DemoAccounts.variable(k, "TOTP"), "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        }
        return env;
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(pg.getJdbcUrl(), "northline", "northline");
    }
}
