package ca.northline.merchants.application;

import ca.northline.merchants.api.RestrictedLicences.Licence;
import ca.northline.shared.Bytes;
import ca.northline.shared.MerchantScope;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Studio (submit, list) and console (queue, decide, read the document) sides of restricted-class licences. */
public final class RestrictedLicenceUseCases {
    private RestrictedLicenceUseCases() {}

    public interface SubmitLicence {
        record Command(
                String merchantId,
                String actorId,
                String actorRole,
                @Nullable String ageClass,
                @Nullable String licenceNumber,
                @Nullable LocalDate expiresOn,
                @Nullable String fileName,
                @Nullable String contentType,
                @Nullable Bytes bytes) {}

        Licence submit(Command command);
    }

    public interface LicenceQueue {
        /** @param status {@code pending} (default) or a decided status; scope = the console's place filter */
        List<QueueItem> queue(MerchantScope scope, @Nullable String status);

        QueueItem decide(Decision decision);

        Documents.ReadDocument.Content document(String licenceId);
    }

    /** @param approve false = reject, which needs a reason */
    public record Decision(
            String licenceId,
            boolean approve,
            @Nullable String reason,
            @Nullable String note,
            String staffId,
            String staffRoles) {}

    /**
     * @param businessProvince the business's province now (a licence counts only there)
     */
    public record QueueItem(
            Licence licence, String businessName, @Nullable String businessProvince) {}

    public interface LicenceJobs {
        /** Approved licences past their expiry → expired (and listings hidden); returns how many. */
        int expireDue();

        /** Owners reminded {@code REMIND_DAYS} before expiry, once per licence; returns how many. */
        int remindDue();
    }
}
