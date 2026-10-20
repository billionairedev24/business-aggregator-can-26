package ca.northline.payments.application;

import ca.northline.payments.domain.LedgerEntry;
import java.util.List;

/** Outbound port: the append-only double-entry ledger. */
public interface LedgerRepository {

    /** Posts a balanced set of entries (Σ debits = Σ credits, checked). */
    void post(List<LedgerEntry> entries);

    /** Credit balance of an account (credits − debits). */
    long balance(String account);

    /** S-151: whether anything was posted for the reference yet (one posting per run's pay). */
    boolean posted(String refType, String refId);
}
