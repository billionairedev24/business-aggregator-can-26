package ca.northline.auth.clients;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Reconciles the {@link OAuthClientCatalog} into the {@link RegisteredClientRepository}: creates missing clients,
 * updates the ones whose stored registration differs (a new {@code secret-hash} is a secret rotation), leaves equal ones
 * alone, and <strong>never deletes</strong> — clients stored but no longer configured are logged as
 * {@link Action#NOT_IN_CONFIGURATION}. Idempotent; replicas starting together serialise on a Postgres advisory lock.
 */
@Slf4j
@RequiredArgsConstructor
final class OAuthClientSync {

    /** {@code pg_advisory_xact_lock} key shared by every replica and the admin command. */
    static final long LOCK_KEY = 0x4e4c_4f41_7574_6863L; // "NLOAuthc"

    private static final List<Field> FIELDS = List.of(
            new Field("secret", RegisteredClient::getClientSecret, OAuthClientSync::sameSecret),
            new Field("name", RegisteredClient::getClientName),
            new Field("authentication-methods", RegisteredClient::getClientAuthenticationMethods),
            new Field("grant-types", RegisteredClient::getAuthorizationGrantTypes),
            new Field("redirect-uris", RegisteredClient::getRedirectUris),
            new Field("post-logout-redirect-uris", RegisteredClient::getPostLogoutRedirectUris),
            new Field("scopes", RegisteredClient::getScopes),
            new Field("client-settings", c -> c.getClientSettings().getSettings()),
            new Field("token-settings", c -> c.getTokenSettings().getSettings()));

    private static final String NOOP = "{noop}";
    private static final PasswordEncoder ENCODERS = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private final OAuthClientCatalog catalog;
    private final RegisteredClientRepository repository;
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;

    /** What {@link #sync()} does, or did, for one client. */
    enum Action {
        CREATE,
        UPDATE,
        UNCHANGED,
        NOT_IN_CONFIGURATION
    }

    /** One client's outcome; {@code changes} names the differing fields of an update (never their values). */
    record Outcome(String clientId, Action action, List<String> changes) {
        @Override
        public String toString() {
            return switch (action) {
                case CREATE -> clientId + ": create";
                case UPDATE -> clientId + ": update " + String.join(", ", changes);
                case UNCHANGED -> clientId + ": up to date";
                case NOT_IN_CONFIGURATION -> clientId + ": stored but not in configuration (left as is)";
            };
        }
    }

    private record Field(
            String name,
            Function<RegisteredClient, @Nullable Object> value,
            BiPredicate<RegisteredClient, RegisteredClient> same) {

        Field(String name, Function<RegisteredClient, @Nullable Object> value) {
            this(name, value, (a, b) -> Objects.equals(value.apply(a), value.apply(b)));
        }

        boolean differs(RegisteredClient wanted, RegisteredClient stored) {
            return !same.test(wanted, stored);
        }
    }

    /**
     * Equal hashes, or a configured {@code {noop}} secret that the stored hash matches: Spring Authorization Server
     * re-encodes a {@code {noop}} secret as {@code {bcrypt}} on its first successful use, which is not a change.
     */
    private static boolean sameSecret(RegisteredClient wanted, RegisteredClient stored) {
        var configured = wanted.getClientSecret();
        var current = stored.getClientSecret();
        if (Objects.equals(configured, current)) {
            return true;
        }
        return configured != null
                && current != null
                && configured.startsWith(NOOP)
                && ENCODERS.matches(configured.substring(NOOP.length()), current);
    }

    /** Compares configuration with the database without writing anything ({@code oauthClients list}). */
    List<Outcome> plan() {
        return Objects.requireNonNull(transactions.execute(_ -> compute(false)));
    }

    /** Creates and updates what {@link #plan()} reports, in one transaction, under the advisory lock. */
    List<Outcome> sync() {
        var outcomes = Objects.requireNonNull(transactions.execute(_ -> {
            jdbc.queryForList("select pg_advisory_xact_lock(?)", LOCK_KEY);
            return compute(true);
        }));
        for (var o : outcomes) {
            if (o.action() == Action.NOT_IN_CONFIGURATION) {
                log.warn("OAuth client {} — delete it by hand once it is really retired", o);
            } else if (o.action() == Action.UNCHANGED) {
                log.debug("OAuth client {}", o);
            } else {
                log.info("OAuth client {}", o);
            }
        }
        return outcomes;
    }

    private List<Outcome> compute(boolean apply) {
        var outcomes = new ArrayList<Outcome>();
        for (var declared : catalog.clients()) {
            var existing = repository.findByClientId(declared.clientId());
            var wanted = declared.registration(existing);
            if (existing == null) {
                outcomes.add(new Outcome(declared.clientId(), Action.CREATE, List.of()));
            } else {
                var changes = FIELDS.stream()
                        .filter(f -> f.differs(wanted, existing))
                        .map(Field::name)
                        .toList();
                outcomes.add(new Outcome(
                        declared.clientId(), changes.isEmpty() ? Action.UNCHANGED : Action.UPDATE, changes));
                if (changes.isEmpty()) {
                    continue;
                }
            }
            if (apply) {
                repository.save(wanted);
            }
        }
        jdbc.queryForList("select client_id from oauth2_registered_client order by client_id", String.class).stream()
                .filter(id -> !catalog.declares(id))
                // S-127: agents registered at run time from their Client ID Metadata Document aren't configuration
                .filter(id -> !id.startsWith("https://") && !id.startsWith("http://"))
                .forEach(id -> outcomes.add(new Outcome(id, Action.NOT_IN_CONFIGURATION, List.of())));
        return List.copyOf(outcomes);
    }
}
