package ca.northline.shared.storage;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only when {@code northline.storage.provider} is {@code s3}, {@code gcs} or {@code azure}: a module's
 * storage port implemented over the {@link ObjectStore} bean, whatever the profile.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(StorageProviderCondition.class)
public @interface UsesObjectStorage {}
