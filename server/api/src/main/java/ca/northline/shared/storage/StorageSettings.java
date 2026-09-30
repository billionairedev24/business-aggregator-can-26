package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import org.springframework.util.StringUtils;

/** Settings every provider needs. */
final class StorageSettings {

    private StorageSettings() {}

    static String bucket(StorageProperties props) {
        var bucket = props.bucket();
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("STORAGE_BUCKET is required for STORAGE_PROVIDER=" + props.provider());
        }
        return bucket;
    }
}
