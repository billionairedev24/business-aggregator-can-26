package ca.northline.identity.web;

import ca.northline.identity.application.ViewMyProfile;
import ca.northline.identity.domain.UserProfile;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} — the signed-in person (same user shape as the BFF's {@code /bff/session}, plus the second
 * factor and whether this token carries {@code acr=mfa}). Any signed-in user.
 */
@RestController
@RequiredArgsConstructor
class MeController {

    private final ViewMyProfile viewMyProfile;

    /** The profile. {@code memberSince} is an ISO date. */
    record MeResponse(
            String id,
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            String initials,
            String locale,
            String memberSince,
            @Nullable String mfaPrimary,
            boolean mfa) {

        static MeResponse of(UserProfile p, boolean mfa) {
            return new MeResponse(
                    p.id(),
                    p.firstName(),
                    p.lastName(),
                    p.email(),
                    p.phone(),
                    p.initials(),
                    p.locale(),
                    p.memberSince().toString(),
                    p.mfaPrimary(),
                    mfa);
        }
    }

    @GetMapping("/api/v1/me")
    MeResponse me(CurrentUser user) {
        return MeResponse.of(viewMyProfile.of(user.userId()), user.mfa());
    }
}
