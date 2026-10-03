package ca.northline.config;

import ca.northline.email.EmailSender;
import ca.northline.sms.SmsTransport;
import java.lang.reflect.Modifier;
import java.time.Clock;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * LOCAL PROFILE ONLY (S-117): the api's outbox for the end-to-end suite. Every {@link EmailSender} and {@link
 * SmsTransport} bean is proxied so what it sends is also kept in {@link DevOutbox}, which {@code
 * config.web.DevOutboxController} serves. Neither the beans nor the route exist under any other profile —
 * DevOnlyRoutesTest checks that under {@code prod}.
 */
@Profile("local")
@Configuration(proxyBeanMethods = false)
class DevOutboxConfig {

    @Bean
    DevOutbox devOutbox(Clock clock) {
        return new DevOutbox(clock);
    }

    /** Static: a post-processor is created before the other beans; the outbox is looked up when the first one comes. */
    @Bean
    static BeanPostProcessor devOutboxRecording(ObjectProvider<DevOutbox> outbox) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof EmailSender || bean instanceof SmsTransport ? recording(bean, outbox) : bean;
            }
        };
    }

    /**
     * A proxy that passes every call on and then records it. A subclass proxy where the bean's class allows it, so code
     * that injects the concrete class still gets it; the library's adapters are final and injected by interface.
     */
    static Object recording(Object bean, ObjectProvider<DevOutbox> outbox) {
        var factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(!Modifier.isFinal(bean.getClass().getModifiers()));
        factory.addAdvice((MethodInterceptor) invocation -> {
            var result = invocation.proceed();
            outbox.getObject().observe(invocation.getMethod().getName(), invocation.getArguments());
            return result;
        });
        return factory.getProxy(bean.getClass().getClassLoader());
    }
}
