package ca.northline.shared.storage;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only when {@code northline.storage.provider} is {@code local} (the default): the modules' disk fakes
 * (with {@code @Profile({"local", "test"})}) and their fail-loudly placeholders (other profiles).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(StorageProviderCondition.class)
public @interface UsesLocalStorage {}
