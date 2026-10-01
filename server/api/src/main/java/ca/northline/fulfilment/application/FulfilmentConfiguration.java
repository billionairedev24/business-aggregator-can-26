package ca.northline.fulfilment.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FulfilmentProperties.class)
class FulfilmentConfiguration {}
