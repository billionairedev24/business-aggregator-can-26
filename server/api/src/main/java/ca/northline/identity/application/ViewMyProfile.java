package ca.northline.identity.application;

import ca.northline.identity.domain.UserProfile;

/** The signed-in person's profile. Throws {@link ca.northline.shared.NotFound} for an unknown user. */
public interface ViewMyProfile {
    UserProfile of(String userId);
}
