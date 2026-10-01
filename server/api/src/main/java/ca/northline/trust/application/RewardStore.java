package ca.northline.trust.application;

import ca.northline.trust.domain.MerchantReward;
import java.util.Optional;

/** Outbound port (S-75): {@code trust.merchant_rewards}, one row per business. */
public interface RewardStore {

    Optional<MerchantReward> find(String merchantId);

    void save(MerchantReward reward, String actorId);
}
