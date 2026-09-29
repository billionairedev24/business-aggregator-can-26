package ca.northline.booking.web;

import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * validation-rules.md § Quote › line.amount: "&gt; 0 unless kind=discount" → "Enter an amount.". Class-level on the
 * line (it needs the kind); reported on {@code lines[i].unitCents} with rule {@code positive_unless_discount}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PositiveUnlessDiscount.Validator.class)
@interface PositiveUnlessDiscount {
    String message() default QuoteContent.LINE_AMOUNT_REQUIRED;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PositiveUnlessDiscount, QuoteLineBody> {
        @Override
        public boolean isValid(QuoteLineBody line, ConstraintValidatorContext context) {
            var unit = line.unitCents();
            if (unit == null || line.kind() == null || line.kind() == LineKind.DISCOUNT || unit > 0) {
                return true; // missing values are reported by @NotNull
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(QuoteContent.LINE_AMOUNT_REQUIRED)
                    .addPropertyNode("unitCents")
                    .addConstraintViolation();
            return false;
        }
    }
}
