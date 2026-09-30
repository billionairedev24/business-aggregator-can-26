package ca.northline.food.application;

import ca.northline.food.domain.PosProvider;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the S-36 POS import (serialized as-is, like the other kitchen views). */
public final class PosImportViews {
    private PosImportViews() {}

    /**
     * One POS in Kitchen › Menu › Import. {@code kind}: {@code oauth} (Square, Clover: Connect goes to the POS) or
     * {@code restaurant_id} (Toast: the kitchen enters its restaurant GUID). {@code state}: disconnected | connected |
     * reconnect.
     */
    public record ConnectionView(
            PosProvider provider,
            String kind,
            boolean available,
            String state,
            @Nullable String accountLabel,
            @Nullable Instant connectedAt,
            @Nullable Instant lastImportAt) {}

    /** Connect: the consent page (OAuth POSes), or the connection itself (Toast). */
    public record ConnectStart(
            @Nullable URI authorizationUrl, @Nullable ConnectionView connection) {}

    /** What applying the preview would do. {@code change} codes are lower-case. */
    public record Diff(List<SectionChange> sections, List<GroupChange> groups, List<ItemChange> items, Counts counts) {
        public Diff {
            sections = List.copyOf(sections);
            groups = List.copyOf(groups);
            items = List.copyOf(items);
        }
    }

    /** {@code change}: new (created) · matched (a section of this menu with the same name, or imported before). */
    public record SectionChange(
            String externalId,
            String name,
            String change,
            @Nullable String localId) {}

    /**
     * {@code change}: new · changed · unchanged · problem (skipped, {@code problem} says why). {@code rule}: the pick
     * rule as the builder shows it (exactly / at_least / up_to + count + required).
     */
    public record GroupChange(
            String externalId,
            String name,
            String change,
            String rule,
            int count,
            boolean required,
            int options,
            @Nullable String problem) {}

    /**
     * {@code change}: new · changed ({@code fields}: name, description, price, modifiers, section) · unchanged ·
     * removed (gone from the POS: hidden, never deleted) · problem (skipped). New items arrive as drafts whose
     * allergens the kitchen must confirm.
     */
    public record ItemChange(
            String externalId,
            String name,
            @Nullable String section,
            String change,
            List<String> fields,
            @Nullable Long priceCents,
            @Nullable Long previousPriceCents,
            @Nullable String problem,
            @Nullable String localId) {
        public ItemChange {
            fields = List.copyOf(fields);
        }
    }

    public record Counts(
            int newItems,
            int changedItems,
            int unchangedItems,
            int removedItems,
            int problems,
            int newSections,
            int newGroups,
            int changedGroups) {}

    /** A preview ("Review changes"); {@code status}: preview · applied · discarded. */
    public record Preview(
            String id,
            PosProvider provider,
            String menuId,
            String status,
            Diff diff,
            Instant createdAt,
            @Nullable Instant appliedAt) {}

    public record Applied(
            int itemsCreated,
            int itemsUpdated,
            int itemsHidden,
            int sectionsCreated,
            int groupsCreated,
            int groupsUpdated,
            int skipped) {}
}
