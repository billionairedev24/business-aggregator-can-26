package ca.northline.merchants.domain;

/** What the edge (Gateway listener + certificate) reports for one custom domain after a reconcile. */
public sealed interface EdgeObservation {

    /** Not on the edge (never added, or removed because the page may not serve). */
    record Absent() implements EdgeObservation {}

    /** On the edge, certificate not issued yet. {@code requested}: its certificate was requested just now. */
    record Provisioning(boolean requested) implements EdgeObservation {}

    /** Certificate issued and the listener programmed: serving. */
    record Ready() implements EdgeObservation {}

    /** The certificate authority refused (e.g. the HTTP-01 challenge failed). */
    record Failed(String reason) implements EdgeObservation {}

    /** Wanted but not added this time: rate limit or capacity. */
    record Deferred(DomainProblem problem) implements EdgeObservation {}

    EdgeObservation ABSENT = new Absent();
    EdgeObservation READY = new Ready();
}
