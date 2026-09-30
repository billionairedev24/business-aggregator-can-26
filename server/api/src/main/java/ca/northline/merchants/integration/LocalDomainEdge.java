package ca.northline.merchants.integration;

import ca.northline.merchants.application.DomainEdge;
import ca.northline.merchants.domain.DomainProblem;
import ca.northline.merchants.domain.EdgeObservation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOCAL / TEST ONLY ({@code DOMAINS_EDGE_PROVIDER=local}): an edge in memory. A wanted domain is ready at once (within
 * the allowance), an unwanted one is dropped — enough to click a domain through to "live" on a laptop.
 */
final class LocalDomainEdge implements DomainEdge {

    private final Set<String> serving = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized Map<String, EdgeObservation> reconcile(Set<String> wanted, int newAllowance) {
        var seen = new HashMap<String, EdgeObservation>();
        for (var host : Set.copyOf(serving)) {
            if (!wanted.contains(host)) {
                serving.remove(host);
                seen.put(host, EdgeObservation.ABSENT);
            }
        }
        var allowance = newAllowance;
        for (var host : wanted) {
            if (serving.contains(host)) {
                seen.put(host, EdgeObservation.READY);
            } else if (allowance > 0) {
                allowance--;
                serving.add(host);
                seen.put(host, EdgeObservation.READY);
            } else {
                seen.put(host, new EdgeObservation.Deferred(DomainProblem.RATE_LIMITED));
            }
        }
        return seen;
    }
}
