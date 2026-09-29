package ca.northline.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.PlatformAutoConfiguration;
import ca.northline.support.ObjectStorageContainers;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Which {@link ObjectStore} (and which module adapters) {@code STORAGE_PROVIDER} and the profile select. */
class ObjectStorageConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformAutoConfiguration.class))
            .withUserConfiguration(ObjectStorageConfiguration.class, Adapters.class)
            .withBean(Clock.class, Clock::systemUTC);

    /** Stand-ins for a module's local fake and object-store adapter. */
    @Configuration(proxyBeanMethods = false)
    static class Adapters {
        @Bean
        @UsesLocalStorage
        String localFake() {
            return "local";
        }

        @Bean
        @UsesObjectStorage
        String objectAdapter() {
            return "object";
        }
    }

    @Test
    void localIsTheDefault_aFolderUnderLocalAndTest() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasSingleBean(ObjectStore.class);
            assertThat(context).hasBean("localFake").doesNotHaveBean("objectAdapter");
            assertThat(context.getBean(ObjectStore.class))
                    .extracting("provider")
                    .isInstanceOf(FileSystemObjectStore.class);
        });
    }

    @Test
    void localUnderDevStartsWithoutAnObjectStore_modulesKeepTheirPlaceholders() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ObjectStore.class);
            assertThat(context).hasBean("localFake");
        });
    }

    @Test
    void localIsRefusedUnderStagingAndProd() {
        for (var profile : new String[] {"staging", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("STORAGE_PROVIDER=local is not allowed under staging/prod"));
        }
    }

    @Test
    void s3SelectsTheObjectStoreAdapters_evenUnderTheLocalProfile() {
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "northline.storage.provider=s3",
                        "northline.storage.bucket=northline-local",
                        "northline.storage.endpoint=http://localhost:9100",
                        "northline.storage.access-key=a",
                        "northline.storage.secret-key=b",
                        "northline.storage.path-style=true")
                .run(context -> {
                    assertThat(context.getBean(ObjectStore.class))
                            .extracting("provider")
                            .isInstanceOf(S3ObjectStore.class);
                    assertThat(context).hasBean("objectAdapter").doesNotHaveBean("localFake");
                });
    }

    @Test
    void s3UnderProdNeedsNoStaticKeys() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "northline.storage.provider=S3",
                        "northline.storage.bucket=northline-prod",
                        "northline.storage.encryption-key=alias/northline-uploads")
                .run(context -> assertThat(context.getBean(ObjectStore.class))
                        .extracting("provider")
                        .isInstanceOf(S3ObjectStore.class));
    }

    @Test
    void gcsAndAzure() {
        runner.withPropertyValues(
                        "northline.storage.provider=gcs",
                        "northline.storage.bucket=b",
                        "northline.storage.endpoint=http://localhost:4443")
                .run(context -> assertThat(context.getBean(ObjectStore.class))
                        .extracting("provider")
                        .isInstanceOf(GcsObjectStore.class));
        runner.withPropertyValues(
                        "northline.storage.provider=azure",
                        "northline.storage.bucket=c",
                        "northline.storage.endpoint=" + "http://127.0.0.1:10000/"
                                + ObjectStorageContainers.AZURITE_ACCOUNT,
                        "northline.storage.access-key=" + ObjectStorageContainers.AZURITE_ACCOUNT,
                        "northline.storage.secret-key=" + ObjectStorageContainers.AZURITE_KEY)
                .run(context -> assertThat(context.getBean(ObjectStore.class))
                        .extracting("provider")
                        .isInstanceOf(AzureBlobObjectStore.class));
    }

    @Test
    void missingSettingsFailStartUp() {
        runner.withPropertyValues("northline.storage.provider=azure", "northline.storage.bucket=c")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("STORAGE_ENDPOINT"));
        runner.withPropertyValues("northline.storage.provider=s3", "northline.storage.bucket=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("STORAGE_BUCKET is required"));
    }

    @Test
    void aVirusScannerBeanIsUsed() {
        VirusScanner scanner = (_, _, _) -> new VirusScanner.Verdict.Clean();
        runner.withPropertyValues("spring.profiles.active=test")
                .withBean(VirusScanner.class, () -> scanner)
                .run(context -> assertThat(context.getBean(ObjectStore.class))
                        .extracting("scanner")
                        .isSameAs(scanner));
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context.getBean(ObjectStore.class))
                        .extracting("scanner")
                        .isSameAs(VirusScanner.NONE));
    }
}
