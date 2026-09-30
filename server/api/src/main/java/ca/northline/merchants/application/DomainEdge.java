package ca.northline.merchants.application;

import ca.northline.merchants.domain.EdgeObservation;
import java.util.Map;
import java.util.Set;

/**
 * Outbound port: the part of the edge that serves merchants' own domains (S-31). {@code northline.domains.edge.provider}
 * ({@code DOMAINS_EDGE_PROVIDER}): {@code local} = in memory, ready at once (local/test); {@code kubernetes} = Gateway API
 * listeners + cert-manager Certificates + HTTPRoutes written into the app's namespace (docs/runbooks/custom-domains.md).
 */
public interface DomainEdge {

    /**
     * Makes the edge serve exactly {@code wanted}: domains not wanted any more are removed, new ones added while
     * {@code newAllowance} (certificates that may be requested now) and the edge's capacity last.
     *
     * @return what the edge reports for every domain it was asked about or still held; a domain not in the map is absent
     */
    Map<String, EdgeObservation> reconcile(Set<String> wanted, int newAllowance);
}
