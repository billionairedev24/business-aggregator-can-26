package ca.northline.identity.application;

import ca.northline.identity.domain.UserProfile;
import ca.northline.shared.NotFound;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProfileService implements ViewMyProfile {

    private final UserProfiles profiles;

    @Override
    public UserProfile of(String userId) {
        return profiles.find(userId).orElseThrow(() -> new NotFound("user", userId));
    }
}
