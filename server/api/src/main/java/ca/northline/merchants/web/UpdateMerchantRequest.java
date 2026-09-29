package ca.northline.merchants.web;

import ca.northline.merchants.domain.DisplayName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/merchants/{id}} body. Messages come from the domain constants (validation-rules.md). Fields are
 * declared non-null because handlers only see the body after {@code @Valid} passed.
 */
record UpdateMerchantRequest(
        @NotBlank(message = DisplayName.REQUIRED)
        @Size(min = DisplayName.MIN, message = DisplayName.TOO_SHORT)
        @Size(max = DisplayName.MAX, message = DisplayName.TOO_LONG)
        String displayName) {}
