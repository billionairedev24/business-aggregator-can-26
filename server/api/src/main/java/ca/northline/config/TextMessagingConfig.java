package ca.northline.config;

import ca.northline.sms.SmsTransportConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The shared SMS library's transport (S-27, {@code server/sms}, {@code northline.sms.provider}) for the api's own text
 * messages: team invitations to a mobile number. Notifications about money events are sent by the worker.
 */
@Configuration(proxyBeanMethods = false)
@Import(SmsTransportConfiguration.class)
class TextMessagingConfig {}
