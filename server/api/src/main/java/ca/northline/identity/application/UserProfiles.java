package ca.northline.identity.application;

import ca.northline.identity.domain.UserProfile;
import java.util.Optional;

/** Outbound port: profile reads from {@code identity.users}. */
public interface UserProfiles {
    Optional<UserProfile> find(String userId);
}
