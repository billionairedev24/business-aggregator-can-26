package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import java.nio.file.Path;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * The {@link ObjectStore} bean for {@code northline.storage.provider} ({@code STORAGE_PROVIDER}), always wrapped in
 * {@link GuardedObjectStore} (keys, TTL, the {@link VirusScanner} bean if one is declared). Only the selected provider's SDK client is created.
 * {@code local}: a folder under the {@code local}/{@code test} profiles, nothing under {@code dev} (the modules'
 * placeholders fail loudly), and refused under {@code staging}/{@code prod}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class ObjectStorageConfiguration {

    static final String PROVIDER = StorageProviderCondition.PROPERTY;

    /** A {@link VirusScanner} bean when one is declared, else {@link VirusScanner#NONE}. */
    static GuardedObjectStore guarded(ObjectStore provider, ObjectProvider<VirusScanner> scanners) {
        var scanner = scanners.getIfUnique();
        if (scanner == null) {
            return new GuardedObjectStore(provider, VirusScanner.NONE);
        }
        log.info("Uploads are scanned by {}", scanner.getClass().getName());
        return new GuardedObjectStore(provider, scanner);
    }

    @Configuration(proxyBeanMethods = false)
    @UsesLocalStorage
    static class Local {

        /** Throws when a deployed environment would keep uploads on a pod's disk. */
        @Bean
        LocalStorageGuard localStorageGuard(Environment environment) {
            if (environment.matchesProfiles("staging | prod")) {
                throw new IllegalStateException("STORAGE_PROVIDER=local is not allowed under staging/prod: set"
                        + " STORAGE_PROVIDER to s3, gcs or azure and STORAGE_BUCKET (docs/runbooks/README.md)");
            }
            return new LocalStorageGuard();
        }

        @Bean
        @Profile({"local", "test"})
        GuardedObjectStore objectStore(
                @Value("${northline.storage.local-dir:${java.io.tmpdir}/northline-objects}") Path dir,
                ObjectProvider<VirusScanner> scanners) {
            return guarded(new FileSystemObjectStore(dir), scanners);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "s3")
    static class S3 {
        @Bean
        GuardedObjectStore objectStore(StorageProperties props, ObjectProvider<VirusScanner> scanners) {
            return guarded(S3ObjectStore.create(props), scanners);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "gcs")
    static class Gcs {
        @Bean
        GuardedObjectStore objectStore(StorageProperties props, ObjectProvider<VirusScanner> scanners) {
            return guarded(GcsObjectStore.create(props, null), scanners);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "azure")
    static class Azure {
        @Bean
        GuardedObjectStore objectStore(StorageProperties props, ObjectProvider<VirusScanner> scanners, Clock clock) {
            return guarded(AzureBlobObjectStore.create(props, clock), scanners);
        }
    }

    /** Marker bean: its creation is the {@code local}-provider check. */
    static final class LocalStorageGuard {}
}
