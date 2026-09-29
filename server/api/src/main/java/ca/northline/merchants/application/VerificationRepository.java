package ca.northline.merchants.application;

import ca.northline.merchants.domain.Verification;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code merchants.verifications} rows of one merchant. */
public interface VerificationRepository {
    /** In checklist order. */
    List<Verification> listFor(String merchantId);

    Optional<Verification> find(String merchantId, String verificationId);

    /** Makes the stored checklist equal to {@code checklist}: inserts new rows, updates kept ones, deletes the rest. */
    void replace(String merchantId, List<Verification> checklist);

    void save(Verification verification);
}
