package ca.northline.messaging.adapters;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.messaging.application.MerchantProfiles;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link MerchantProfiles} from the merchants module's public directory (S-37). */
@Component
@RequiredArgsConstructor
class DirectoryMerchantProfiles implements MerchantProfiles {

    private final MerchantDirectory directory;

    @Override
    public Optional<Profile> profile(String merchantId) {
        return directory.profile(merchantId).map(p -> new Profile(Objects.requireNonNullElse(p.type(), ""), p.tier()));
    }
}
