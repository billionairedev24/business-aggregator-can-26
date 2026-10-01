package ca.northline.auth.mcp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** S-127 beans; the authorization server's chain uses them ({@code AuthorizationServerConfig}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpAuthProperties.class)
class McpAuthConfig {

    @Bean
    ResourceIndicators resourceIndicators(McpAuthProperties props) {
        return new ResourceIndicators(props);
    }
}
