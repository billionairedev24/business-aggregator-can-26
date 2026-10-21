package ca.northline.identity.api;

import java.util.Collection;
import java.util.Set;

/**
 * S-157: who of a set of people is a customer of a market — an active account with a saved address in that province
 * or territory (the region model's markets are provinces, DECISIONS "Region-neutral by design"). Used to address an
 * offer to the consenting customers of a market; ids only.
 */
public interface MarketAudience {

    Set<String> inProvince(Collection<String> userIds, String province);
}
