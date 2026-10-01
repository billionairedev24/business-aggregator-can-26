package ca.northline.developer.application;

import ca.northline.developer.domain.ApiKey;
import java.util.List;
import java.util.Optional;

/** Keys across every business, for staff (S-96). */
public interface PlatformDeveloperStore {

    List<ApiKey> allKeys(int limit);

    Optional<ApiKey> keyById(String keyId);
}
