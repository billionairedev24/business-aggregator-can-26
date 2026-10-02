package ca.northline.privacy.adapters;

import ca.northline.privacy.application.PrivacySettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code northline.privacy.*} ({@link PrivacySettings}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacySettings.class)
class PrivacyConfiguration {}
