package ca.northline.trust.application;

import java.util.Map;

/** Outbound port: trust &amp; safety flags ({@code trust.flags}), raised at most once per target and rule. */
public interface TrustFlagStore {

    void raise(
            String id,
            String targetType,
            String targetId,
            String rule,
            String merchantId,
            String actorId,
            Map<String, String> evidence);
}
