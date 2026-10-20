package ca.northline.orders.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AfterSaleProperties} (S-151). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AfterSaleProperties.class)
class AfterSaleConfiguration {}
