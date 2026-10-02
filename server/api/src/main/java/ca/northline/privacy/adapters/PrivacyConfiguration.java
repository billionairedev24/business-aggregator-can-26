package ca.northline.privacy.adapters;

import ca.northline.privacy.application.PrivacySettings;
import ca.northline.privacy.application.RetentionCatalogue;
import ca.northline.privacy.application.RetentionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code northline.privacy.*} ({@link PrivacySettings}) and {@code northline.retention.*}; loads the schedule. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PrivacySettings.class, RetentionSettings.class})
class PrivacyConfiguration {

    /** S-107: the retention schedule (the Privacy Policy's section 6), from {@link RetentionCatalogue#RESOURCE}. */
    @Bean
    RetentionCatalogue retentionCatalogue() {
        return RetentionCatalogue.load();
    }
}
