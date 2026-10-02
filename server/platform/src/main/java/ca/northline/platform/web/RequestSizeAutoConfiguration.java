package ca.northline.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-104: every servlet app (api, auth, bff, worker) caps request bodies — {@code northline.http.max-request-body}
 * (default 5 MB) and {@code northline.http.max-multipart-body} for uploads (default 26 MB, above the api's 25 MB
 * multipart request limit).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
public class RequestSizeAutoConfiguration {

    @Bean
    RequestSizeLimitFilter requestSizeLimitFilter(
            @Value("${northline.http.max-request-body:5MB}") DataSize maxBody,
            @Value("${northline.http.max-multipart-body:26MB}") DataSize maxMultipart) {
        return new RequestSizeLimitFilter(maxBody.toBytes(), maxMultipart.toBytes());
    }
}
