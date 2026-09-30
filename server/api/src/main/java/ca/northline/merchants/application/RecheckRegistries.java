package ca.northline.merchants.application;

/** The scheduled registry re-check (S-23): run by the merchants scheduler, or by tests directly. */
public interface RecheckRegistries {
    /** Rows per call. */
    int BATCH = 50;

    /** Re-checks verified rows last checked more than the re-check interval ago; returns how many were checked. */
    int recheckDue();
}
