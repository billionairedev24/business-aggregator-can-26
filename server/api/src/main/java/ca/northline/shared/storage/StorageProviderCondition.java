package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Backs {@link UsesLocalStorage} and {@link UsesObjectStorage}: binds {@code northline.storage.provider} like the properties do. */
final class StorageProviderCondition extends SpringBootCondition {

    static final String PROPERTY = "northline.storage.provider";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var provider = provider(context);
        var wantsLocal = metadata.isAnnotated(UsesLocalStorage.class.getName());
        var matches = wantsLocal == (provider == StorageProperties.Provider.LOCAL);
        var message = "%s=%s (%s)".formatted(PROPERTY, provider, wantsLocal ? "local fakes" : "object storage");
        return matches ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
    }

    static StorageProperties.Provider provider(ConditionContext context) {
        return Binder.get(context.getEnvironment())
                .bind(PROPERTY, StorageProperties.Provider.class)
                .orElse(StorageProperties.Provider.LOCAL);
    }
}
