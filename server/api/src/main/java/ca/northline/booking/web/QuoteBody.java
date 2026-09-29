package ca.northline.booking.web;

import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Body of "Send quote" and "Revise" (validation-rules.md § Quote). Messages are the constants of {@link QuoteContent},
 * which re-checks everything as a domain invariant.
 */
record QuoteBody(
        @NotEmpty(message = QuoteContent.LINES_REQUIRED)
        @Size(max = QuoteContent.MAX_LINES, message = "At most 50 lines.")
        List<@Valid @NotNull QuoteLineBody> lines,

        @NotBlank(message = QuoteContent.SCOPE_REQUIRED) @Size(max = 4000, message = "At most 4000 characters.")
        String scope,

        @Size(max = 4000, message = "At most 4000 characters.") @Nullable
        String exclusions,

        @Nullable Instant proposedAt,
        @Positive(message = "Enter a duration.") @Nullable Integer durationMin,

        @NotNull(message = QuoteContent.VALID_HOURS_INVALID) Integer validHours,

        @NotNull(message = "Choose a warranty.") Warranty warranty,
        @NotNull(message = "Choose a deposit.") DepositKind depositKind,
        @Nullable Integer depositBps,

        @Size(max = QuoteContent.MAX_ATTACHMENTS, message = QuoteContent.TOO_MANY_ATTACHMENTS) @Nullable
        List<String> attachments) {}
