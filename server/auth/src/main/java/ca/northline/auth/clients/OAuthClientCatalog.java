package ca.northline.auth.clients;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * The validated set of clients configuration declares. Built once; any problem in any client stops start-up (or the
 * admin command) with the full list, before anything is written.
 */
@Slf4j
final class OAuthClientCatalog {

    /** One declared client: an app/BFF ({@link ClientSpec}) or a partner ({@link PartnerSpec}, S-30). */
    record Declared(
            String clientId,
            @Nullable ClientSpec spec,
            @Nullable PartnerSpec partner) {
        Declared(String clientId, ClientSpec spec) {
            this(clientId, spec, null);
        }

        RegisteredClient registration(@Nullable RegisteredClient existing) {
            if (partner != null) {
                return partner.toRegisteredClient(clientId.substring(PartnerSpec.CLIENT_ID_PREFIX.length()), existing);
            }
            return Objects.requireNonNull(spec, "a client or a partner").toRegisteredClient(clientId, existing);
        }

        @Nullable
        String secretHash() {
            return spec == null ? null : spec.secretHash();
        }
    }

    private final List<Declared> clients;

    OAuthClientCatalog(OAuthClientProperties props, ClientPolicy policy) {
        var problems = new ArrayList<String>();
        var declared = new ArrayList<Declared>();
        props.clients().forEach((clientId, spec) -> {
            if (spec.notConfigured()) {
                log.info("OAuth client {} is optional and has no secret-hash here: not registered", clientId);
                return;
            }
            spec.problems(clientId, policy).forEach(p -> problems.add(clientId + ": " + p));
            declared.add(new Declared(clientId, spec));
        });
        props.partners().forEach((name, partner) -> {
            var clientId = PartnerSpec.clientId(name);
            partner.problems(name, policy).forEach(p -> problems.add(clientId + ": " + p));
            if (props.clients().containsKey(clientId)) {
                problems.add(clientId + ": declared both as a client and as a partner");
            }
            declared.add(new Declared(clientId, null, partner));
        });
        declared.stream()
                .filter(d -> d.secretHash() != null)
                .collect(Collectors.groupingBy(
                        d -> String.valueOf(d.secretHash()),
                        Collectors.mapping(Declared::clientId, Collectors.toList())))
                .values()
                .stream()
                .filter(ids -> ids.size() > 1)
                .forEach(ids -> problems.add(ids + ": share one secret-hash; every client needs its own secret"));
        if (declared.isEmpty() && policy == ClientPolicy.STRICT) {
            problems.add("no client is configured under northline.oauth.clients");
        }
        if (!problems.isEmpty()) {
            throw new InvalidClientConfiguration(problems);
        }
        declared.stream()
                .filter(d -> String.valueOf(d.secretHash()).startsWith("{noop}"))
                .filter(_ -> policy == ClientPolicy.DEV)
                .forEach(d -> log.warn(
                        "OAuth client {} has an unhashed {noop} secret: use {bcrypt} outside a rehearsal",
                        d.clientId()));
        this.clients = List.copyOf(declared);
    }

    List<Declared> clients() {
        return clients;
    }

    boolean declares(String clientId) {
        return clients.stream().anyMatch(d -> d.clientId().equals(clientId));
    }

    /** Invalid {@code northline.oauth.clients} configuration, every problem listed. */
    static final class InvalidClientConfiguration extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        InvalidClientConfiguration(List<String> problems) {
            super("Invalid OAuth client configuration (northline.oauth.clients, docs/runbooks/README.md § OAuth"
                    + " clients):\n  - " + String.join("\n  - ", problems));
        }
    }
}
