package ca.northline.shared.web;

import ca.northline.shared.security.MerchantAccess;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
class WebSupportConfig implements WebMvcConfigurer {

    private final MerchantAccess access;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MerchantAccessInterceptor(access)).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(access));
        resolvers.add(new CurrentMemberArgumentResolver());
    }
}
