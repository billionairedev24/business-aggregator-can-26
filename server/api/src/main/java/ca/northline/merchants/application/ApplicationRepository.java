package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantApplication;
import java.util.Optional;

/** Outbound port: the onboarding view of a merchant (merchants row + principals + categories). */
public interface ApplicationRepository {
    Optional<MerchantApplication> findById(String merchantId);

    /** Inserts the applicant and makes {@code ownerId} its owner ({@code merchant_members}). */
    void insert(MerchantApplication application, String ownerId);

    /** Updates the merchants row and replaces principals and categories. */
    void save(MerchantApplication application);

    /** Points principals at or above {@code thresholdPct} (0 = all) at the KYC verification row. */
    void linkPrincipalKyc(String merchantId, String kycVerificationId, int thresholdPct);

    boolean businessNumberTaken(String businessNumber, String exceptMerchantId);
}
