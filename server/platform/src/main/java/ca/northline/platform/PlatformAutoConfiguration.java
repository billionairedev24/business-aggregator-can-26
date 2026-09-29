package ca.northline.platform;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Binds the provider settings and logs which adapters the configuration asks for. */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({StorageProperties.class, KmsProperties.class, EmailProperties.class, SmsProperties.class
})
public class PlatformAutoConfiguration {

    @Bean
    SmartInitializingSingleton providerBanner(
            StorageProperties storage, KmsProperties kms, EmailProperties email, SmsProperties sms) {
        return () -> log.info(
                "Providers: storage={} kms={} email={} sms={}",
                lower(storage.provider()),
                lower(kms.provider()),
                lower(email.provider()),
                lower(sms.provider()));
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
