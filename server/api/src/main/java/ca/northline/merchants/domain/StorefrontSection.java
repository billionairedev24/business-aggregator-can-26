package ca.northline.merchants.domain;

import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/** One ordered, toggleable section of a storefront ({@code merchants.storefront_sections}). */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StorefrontSection {
    private final String id;
    private final SectionKind kind;
    private int position;
    private boolean enabled;
    private Map<String, Object> settings;
    private final Instant createdAt;
    private Instant updatedAt;

    static StorefrontSection create(SectionKind kind, int position, Instant at) {
        return new StorefrontSection(Ids.next(), kind, position, true, Map.of(), at, at);
    }

    /** @return whether anything changed */
    boolean arrange(int newPosition, boolean on, @Nullable Map<String, Object> newSettings, Instant at) {
        var next = newSettings == null ? settings : Map.copyOf(newSettings);
        boolean changed = newPosition != position || on != enabled || !next.equals(settings);
        position = newPosition;
        enabled = kind.required() || on;
        settings = next;
        if (changed) {
            updatedAt = at;
        }
        return changed;
    }
}
