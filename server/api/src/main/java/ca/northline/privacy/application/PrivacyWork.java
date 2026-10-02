package ca.northline.privacy.application;

/** The privacy pipeline's scheduled work (the scheduler calls it; tests call it directly). */
public interface PrivacyWork {

    /** Runs due exports and erasures and due erasure steps; returns how many requests completed. */
    int runDue();

    /** Runs one request's due work now; true when it completed. */
    boolean run(String requestId);

    /** Deletes expired export bundles; returns how many. */
    int sweep();
}
