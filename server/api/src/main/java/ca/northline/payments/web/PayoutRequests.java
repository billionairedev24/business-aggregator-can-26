package ca.northline.payments.web;

import static ca.northline.payments.domain.PayoutMessages.ACCOUNT_FORMAT;
import static ca.northline.payments.domain.PayoutMessages.ACCOUNT_PATTERN;
import static ca.northline.payments.domain.PayoutMessages.AMOUNT_MIN;
import static ca.northline.payments.domain.PayoutMessages.AMOUNT_REQUIRED;
import static ca.northline.payments.domain.PayoutMessages.HOLDER_TOO_LONG;
import static ca.northline.payments.domain.PayoutMessages.INSTITUTION_FORMAT;
import static ca.northline.payments.domain.PayoutMessages.INSTITUTION_PATTERN;
import static ca.northline.payments.domain.PayoutMessages.METHOD_REQUIRED;
import static ca.northline.payments.domain.PayoutMessages.RESERVE_REQUIRED;
import static ca.northline.payments.domain.PayoutMessages.SCHEDULE_REQUIRED;
import static ca.northline.payments.domain.PayoutMessages.TRANSIT_FORMAT;
import static ca.northline.payments.domain.PayoutMessages.TRANSIT_PATTERN;

import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutMessages;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.shared.RuleViolation;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/** Request bodies of the Payouts screen. Messages: {@link PayoutMessages} (ours, en + fr in the Studio). */
final class PayoutRequests {
    private PayoutRequests() {}

    /** {@code POST /payouts/instant}. */
    record InstantPayout(
            @NotNull(message = AMOUNT_REQUIRED) @Min(value = 100, message = AMOUNT_MIN) @Nullable
            Long amountCents) {}

    /** {@code PUT /payouts/schedule} — weekly needs a weekday (1–5), monthly a day of the month. */
    record Schedule(
            @NotNull(message = SCHEDULE_REQUIRED) PayoutSchedule.@Nullable Frequency frequency,
            @Nullable Integer weekday,
            PayoutSchedule.@Nullable MonthlyAnchor monthlyAnchor,
            @NotNull(message = RESERVE_REQUIRED) PayoutSchedule.@Nullable Reserve reserve) {

        PayoutSchedule toSchedule() {
            return new PayoutSchedule(
                    java.util.Objects.requireNonNull(frequency),
                    weekday,
                    monthlyAnchor,
                    java.util.Objects.requireNonNull(reserve));
        }
    }

    /**
     * {@code POST /payouts/bank-accounts}. {@code instant}: {@code linkedAccount} = the bank-account token
     * Stripe.js' {@code collectBankAccountToken} returned and {@code financialConnectionsAccount} ({@code fca_…});
     * {@code manual}: institution (3 digits), transit (5), account (7–12) and holder.
     */
    record BankAccount(
            @NotNull(message = METHOD_REQUIRED) PayoutAccount.@Nullable Method method,

            @Size(max = 255, message = PayoutMessages.LINK_AGAIN) @Nullable
            String linkedAccount,

            @Size(max = 255, message = PayoutMessages.LINK_AGAIN) @Nullable
            String financialConnectionsAccount,

            @Pattern(regexp = INSTITUTION_PATTERN, message = INSTITUTION_FORMAT) @Nullable
            String institution,

            @Pattern(regexp = TRANSIT_PATTERN, message = TRANSIT_FORMAT) @Nullable
            String transit,

            @Pattern(regexp = ACCOUNT_PATTERN, message = ACCOUNT_FORMAT) @Nullable
            String accountNumber,

            @Size(max = 120, message = HOLDER_TOO_LONG) @Nullable
            String holderName) {

        /** Manual entry: every field is required (the format message doubles as the "required" one). */
        void requireManualFields() {
            if (method != PayoutAccount.Method.MANUAL) {
                return;
            }
            var missing = new ArrayList<RuleViolation.Violation>();
            if (blank(accountNumber)) {
                missing.add(new RuleViolation.Violation("accountNumber", "required", ACCOUNT_FORMAT));
            }
            if (blank(holderName)) {
                missing.add(new RuleViolation.Violation("holderName", "required", PayoutMessages.HOLDER_REQUIRED));
            }
            if (blank(institution)) {
                missing.add(new RuleViolation.Violation("institution", "required", INSTITUTION_FORMAT));
            }
            if (blank(transit)) {
                missing.add(new RuleViolation.Violation("transit", "required", TRANSIT_FORMAT));
            }
            if (!missing.isEmpty()) {
                throw new RuleViolation(missing);
            }
        }

        private static boolean blank(@Nullable String s) {
            return s == null || s.isBlank();
        }
    }
}
