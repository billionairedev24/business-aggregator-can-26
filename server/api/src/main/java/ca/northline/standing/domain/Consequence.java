package ca.northline.standing.domain;

import java.time.Instant;
import java.util.Map;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * One consequence ({@code standing.consequences}): what happened to a business or a customer, why (the reason they
 * see), who applied it ({@code system} for the rules) and, once lifted, who lifted it and why.
 *
 * @param cause {@code rating_floor} | {@code off_platform_payment} | {@code provider_no_shows} | {@code missing_photo}
 *     | {@code customer_no_shows} | {@code staff}
 * @param refId the merchants oversight action, or the booking of a photo delay
 * @param endsAt a photo delay: the escrow is released no earlier than this
 */
@With
public record Consequence(
        String id,
        @Nullable String merchantId,
        @Nullable String customerId,
        ConsequenceKind kind,
        String cause,
        String reason,
        @Nullable String refId,
        Map<String, String> detail,
        String appliedBy,
        String appliedRole,
        Instant startedAt,
        @Nullable Instant endsAt,
        boolean active,
        @Nullable Instant liftedAt,
        @Nullable String liftedBy,
        @Nullable String liftedRole,
        @Nullable String liftReason) {

    public static final String SYSTEM = "system";

    public Consequence {
        detail = Map.copyOf(detail);
    }

    public static Consequence ofBusiness(
            String id,
            String merchantId,
            ConsequenceKind kind,
            String cause,
            String reason,
            @Nullable String refId,
            Map<String, String> detail,
            String appliedBy,
            String appliedRole,
            Instant at,
            @Nullable Instant endsAt) {
        return new Consequence(
                id,
                merchantId,
                null,
                kind,
                cause,
                clip(reason),
                refId,
                detail,
                appliedBy,
                appliedRole,
                at,
                endsAt,
                true,
                null,
                null,
                null,
                null);
    }

    public static Consequence ofCustomer(
            String id,
            String customerId,
            ConsequenceKind kind,
            String cause,
            String reason,
            Map<String, String> detail,
            Instant at) {
        return new Consequence(
                id,
                null,
                customerId,
                kind,
                cause,
                clip(reason),
                null,
                detail,
                SYSTEM,
                SYSTEM,
                at,
                null,
                true,
                null,
                null,
                null,
                null);
    }

    /** Whether the business can still appeal it now (active, an appealable kind, within the window). */
    public boolean appealableAt(Instant now) {
        return active && merchantId != null && kind.appealable() && !now.isAfter(startedAt.plus(AppealRules.WINDOW));
    }

    public Instant appealBy() {
        return startedAt.plus(AppealRules.WINDOW);
    }

    public Consequence lift(String by, String role, String reason, Instant at) {
        return withActive(false)
                .withLiftedAt(at)
                .withLiftedBy(by)
                .withLiftedRole(role)
                .withLiftReason(clip(reason));
    }

    private static String clip(String text) {
        var t = text.strip();
        return t.length() > 1000 ? t.substring(0, 999) + "…" : t;
    }
}
