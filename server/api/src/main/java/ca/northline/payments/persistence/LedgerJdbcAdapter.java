package ca.northline.payments.persistence;

import ca.northline.payments.application.LedgerRepository;
import ca.northline.payments.domain.LedgerEntry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The append-only ledger (a trigger refuses UPDATE / DELETE). */
@Repository
@RequiredArgsConstructor
class LedgerJdbcAdapter implements LedgerRepository {

    private final JdbcClient jdbc;

    @Override
    public void post(List<LedgerEntry> entries) {
        var debits = entries.stream().mapToLong(LedgerEntry::debitCents).sum();
        var credits = entries.stream().mapToLong(LedgerEntry::creditCents).sum();
        if (debits != credits) {
            throw new IllegalArgumentException("Unbalanced posting: debits %d ≠ credits %d".formatted(debits, credits));
        }
        for (var e : entries) {
            jdbc.sql("""
                            insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
                            values (:id, :account, :debit, :credit, :refType, :refId, :at)""")
                    .param("id", e.id())
                    .param("account", e.account())
                    .param("debit", e.debitCents())
                    .param("credit", e.creditCents())
                    .param("refType", e.refType())
                    .param("refId", e.refId())
                    .param("at", e.at().atOffset(java.time.ZoneOffset.UTC))
                    .update();
        }
    }

    @Override
    public long balance(String account) {
        return jdbc.sql("""
                        select coalesce(sum(credit_cents - debit_cents), 0)
                          from payments.ledger_entries where account = :account""").param("account", account).query(Long.class).single();
    }
}
