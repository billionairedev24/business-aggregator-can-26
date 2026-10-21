package ca.northline.developer.application;

import ca.northline.developer.api.PartnerClients.Binding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-158): {@code developer.partner_clients} and {@code developer.partner_bindings}. */
public interface PartnerClientStore {

    record Row(
            String id,
            String name,
            String displayName,
            String jwkSetUrl,
            List<String> scopes,
            int tokenTtlSeconds,
            String state,
            int keyVersion,
            @Nullable Instant rotatedAt,
            Instant createdAt) {
        public Row {
            scopes = List.copyOf(scopes);
        }
    }

    List<Row> all();

    Optional<Row> find(String id);

    boolean nameTaken(String name);

    void insert(Row row, String createdBy);

    void rotate(String id, String jwkSetUrl, Instant at);

    void setState(String id, String state, @Nullable String by, Instant at);

    List<Binding> bindings(String partnerId);

    /** Inserts a request; false when the business was already asked (its answer stays). */
    boolean request(String partnerId, String merchantId, String staffId, Instant at);

    boolean remove(String partnerId, String merchantId);

    Optional<Binding> binding(String partnerId, String merchantId);

    /** Locks the binding row for the owner's answer. */
    Optional<Binding> lockBinding(String partnerId, String merchantId);

    void decide(String partnerId, String merchantId, String state, String by, Instant at);

    /** The partners that asked this business, with its answers, newest request first. */
    List<BusinessBinding> ofBusiness(String merchantId);

    record BusinessBinding(Row partner, Binding binding) {}
}
