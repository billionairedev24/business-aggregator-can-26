package ca.northline.payments.web;

import static ca.northline.payments.domain.CaseMessages.CONTEST_REASON_REQUIRED;
import static ca.northline.payments.domain.CaseMessages.OFFER_RANGE;
import static ca.northline.payments.domain.CaseMessages.RESPONSE_MAX;
import static ca.northline.payments.domain.CaseMessages.RESPONSE_TOO_LONG;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Request bodies of the Refunds &amp; disputes screen. Messages: {@code CaseMessages} (ours, en + fr in the Studio). */
final class CaseRequests {
    private CaseRequests() {}

    /** {@code PUT /disputes/{id}/response} — the draft; empty clears it. */
    record Response(
            @Size(max = RESPONSE_MAX, message = RESPONSE_TOO_LONG) @Nullable
            String response) {}

    /** {@code POST /disputes/{id}/goodwill-offer} ("Send 50% goodwill offer"). */
    record GoodwillOffer(
            @NotNull(message = OFFER_RANGE) @Positive(message = OFFER_RANGE) @Nullable
            Long amountCents) {}

    /** {@code POST /refunds/{id}/contest}. */
    record Contest(
            @NotBlank(message = CONTEST_REASON_REQUIRED) @Nullable
            String reason) {}
}
