package ca.northline.standing.application;

import ca.northline.shared.MerchantScope;
import ca.northline.standing.domain.Appeal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code standing.appeals} and {@code appeal_files}. */
public interface AppealStore {

    /** {@code AP-1001}, … */
    String nextNumber();

    /** False when the subject was already appealed (once each). */
    boolean insert(Appeal appeal);

    Optional<Appeal> find(String id);

    /** Locked for a change. */
    Optional<Appeal> lock(String id);

    /** State, assignment, decision and compensation. */
    void update(Appeal appeal);

    void insertFile(String appealId, Appeal.AppealFile file);

    Optional<Appeal> forSubject(Appeal.Subject subject, String subjectId);

    /** Newest first. */
    List<Appeal> ofBusiness(String merchantId, int limit);

    /** Waiting ones (oldest first), then those decided since {@code decidedSince}. */
    List<Appeal> queue(MerchantScope scope, Instant decidedSince, int limit);
}
