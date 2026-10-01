package ca.northline.merchants.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The documents on a business's compliance ledger (licences, insurance, permits, certificates, attestations) for
 * screens outside merchants (S-66: the help form's "Related to" list offers them when a merchant opens a case).
 */
public interface ComplianceDocuments {

    /**
     * @param checkType {@code licence | insurance | wcb | food_cert | inspection | registry | attestation | …}
     * @param registry the issuing registry when known ({@code AMVIC}, {@code CRA}, …)
     * @param label the recorded name of the row, when it has one
     * @param status the row's status as of now ({@code verified | submitted | todo | expired | rejected})
     */
    record Document(
            String id,
            String checkType,
            @Nullable String registry,
            @Nullable String reference,
            @Nullable String label,
            String status,
            @Nullable Instant expiresAt) {}

    /** The ledger's rows, the most urgent (earliest expiry) first. */
    List<Document> documents(String merchantId);
}
