package ca.northline.sms;

import ca.northline.platform.SmsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/**
 * The {@link SmsTransport} bean for apps that send text messages themselves (api: team invitations; worker:
 * notifications). Imported explicitly ({@code @Import}), not an auto-configuration: northline-auth wires its code
 * sender from {@link SmsTransports} in its own {@code SmsConfig}. Only the selected provider's client is created.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
public class SmsTransportConfiguration {

    static final String PROVIDER = "northline.sms.provider";

    @Bean
    @ConditionalOnMissingBean
    SmsTransport smsTransport(
            SmsProperties sms, Environment environment, ObjectProvider<PinpointSmsVoiceV2Client> awsClient) {
        return sms.provider() == SmsProperties.Provider.AWS
                ? SmsTransports.aws(awsClient.getObject(), sms)
                : SmsTransports.of(sms, environment);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "aws")
    static class Aws {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        PinpointSmsVoiceV2Client pinpointSmsVoiceV2Client(SmsProperties sms) {
            return SmsTransports.awsClient(sms);
        }
    }
}
