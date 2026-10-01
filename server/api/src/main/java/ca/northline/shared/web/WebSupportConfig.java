package ca.northline.shared.web;

import ca.northline.shared.security.MerchantAccess;
import ca.northline.shared.security.StaffAccess;
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
    private final StaffAccess staff;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MerchantAccessInterceptor(access)).addPathPatterns("/api/**");
        // S-90: every console handler declares the screen (and actions) it serves; the role decides.
        registry.addInterceptor(new StaffAccessInterceptor(access, staff)).addPathPatterns("/api/v1/console/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(access));
        resolvers.add(new CurrentMemberArgumentResolver());
        resolvers.add(new CurrentStaffArgumentResolver());
    }
}
