package ca.northline.catalogue.application;

import ca.northline.catalogue.api.InstantBook.State;

/** S-156: the business's own instant-book switch (Studio › Availability). 409 while Northline has paused it. */
public interface SwitchInstantBook {

    State set(String merchantId, boolean enabled, String userId);
}
