package ca.northline.identity.web;

import ca.northline.identity.application.AccountSettings.Address;
import ca.northline.identity.application.AccountSettings.AddressChange;
import ca.northline.identity.application.AccountSettings.Household;
import ca.northline.identity.application.AccountSettings.ManageAddresses;
import ca.northline.identity.application.AccountSettings.ManageHousehold;
import ca.northline.identity.application.AccountSettings.ManageProfile;
import ca.northline.identity.application.AccountSettings.NewAddress;
import ca.northline.identity.application.AccountSettings.Profile;
import ca.northline.identity.application.AccountSettings.ProfileChange;
import ca.northline.identity.domain.AccountRules;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer's own account settings (S-59, design 06 account):
 *
 * <pre>
 * GET    /api/v1/me/profile                     the profile (name, contact, pronouns, birthday, reliability)
 * PATCH  /api/v1/me/profile                     {firstName, lastName, email, pronouns?, birthday? "MM-DD"}
 * POST   /api/v1/me/erasure-request             "Delete account…" (staff erase it; idempotent)
 * GET    /api/v1/me/addresses                   the address book, default first
 * POST   /api/v1/me/addresses                   {label?, street, unit?, city, province, postal, note?} → 201
 * PATCH  /api/v1/me/addresses/{id}              {label?, unit?, note?}
 * POST   /api/v1/me/addresses/{id}/default      → the book
 * DELETE /api/v1/me/addresses/{id}              → the book
 * GET    /api/v1/me/household                   members and Northline Plus
 * POST   /api/v1/me/plus                        {plan: monthly|annual} — the 30-day free trial
 * DELETE /api/v1/me/plus                        cancel Plus
 * </pre>
 *
 * Single-factor sessions are accepted, like every {@code /api/v1/me} endpoint. Changing the verified mobile number is
 * northline-auth's (it needs a code), not this controller's.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MyAccountController {

    private final ManageProfile profiles;
    private final ManageAddresses addresses;
    private final ManageHousehold households;

    record ProfileResponse(
            String id,
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            String locale,
            LocalDate memberSince,
            @Nullable String pronouns,
            @Nullable String birthday,
            @Nullable BigDecimal reliability,
            @Nullable Instant erasureRequestedAt) {

        static ProfileResponse of(Profile p) {
            var b = p.birthday();
            return new ProfileResponse(
                    p.id(),
                    p.firstName(),
                    p.lastName(),
                    p.email(),
                    p.phone(),
                    p.locale(),
                    p.memberSince(),
                    p.pronouns(),
                    b == null ? null : "%02d-%02d".formatted(b.getMonthValue(), b.getDayOfMonth()),
                    p.reliability(),
                    p.erasureRequestedAt());
        }
    }

    record ProfileRequest(
            @NotBlank(message = AccountRules.FIRST_NAME_REQUIRED)
            @Size(max = AccountRules.NAME_MAX, message = AccountRules.NAME_TOO_LONG)
            String firstName,

            @NotBlank(message = AccountRules.LAST_NAME_REQUIRED)
            @Size(max = AccountRules.NAME_MAX, message = AccountRules.NAME_TOO_LONG)
            String lastName,

            @NotBlank(message = AccountRules.EMAIL_REQUIRED)
            @Pattern(regexp = AccountRules.EMAIL_PATTERN, message = AccountRules.EMAIL_FORMAT)
            String email,

            @Nullable String pronouns,
            @Nullable String birthday) {}

    record AddressRequest(
            @Nullable @Size(max = 40, message = AccountRules.LABEL)
            String label,

            @NotBlank(message = AccountRules.STREET) @Size(max = 120, message = AccountRules.STREET)
            String street,

            @Nullable @Size(max = 20, message = AccountRules.UNIT)
            String unit,

            @NotBlank(message = AccountRules.CITY) @Size(max = 60, message = AccountRules.CITY)
            String city,

            @NotBlank(message = AccountRules.PROVINCE) String province,

            @NotBlank(message = AccountRules.POSTAL)
            @Pattern(regexp = AccountRules.POSTAL_PATTERN, message = AccountRules.POSTAL)
            String postal,

            @Nullable @Size(max = 200, message = AccountRules.NOTE)
            String note) {}

    record AddressChangeRequest(
            @Nullable @Size(max = 40, message = AccountRules.LABEL)
            String label,

            @Nullable @Size(max = 20, message = AccountRules.UNIT)
            String unit,

            @Nullable @Size(max = 200, message = AccountRules.NOTE)
            String note) {}

    record PlusRequest(
            @NotBlank(message = "Choose monthly or annual.") String plan) {}

    @Operation(summary = "The caller's profile")
    @GetMapping("/profile")
    ResponseEntity<ProfileResponse> profile(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ProfileResponse.of(profiles.profile(user.userId())));
    }

    @Operation(summary = "Update the caller's name, email, pronouns and birthday")
    @PatchMapping("/profile")
    ProfileResponse update(CurrentUser user, @Valid @RequestBody ProfileRequest body) {
        var change = new ProfileChange(
                body.firstName(),
                body.lastName(),
                body.email(),
                body.pronouns(),
                AccountRules.birthday(body.birthday()));
        return ProfileResponse.of(profiles.update(user.userId(), change));
    }

    @Operation(summary = "Ask Northline to delete the caller's account")
    @PostMapping("/erasure-request")
    ProfileResponse erasure(CurrentUser user) {
        return ProfileResponse.of(profiles.requestErasure(user.userId()));
    }

    @Operation(summary = "The caller's address book")
    @GetMapping("/addresses")
    ResponseEntity<ListResponse<Address>> addresses(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(addresses.list(user.userId())));
    }

    @Operation(summary = "Add an address")
    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    Address add(CurrentUser user, @Valid @RequestBody AddressRequest b) {
        return addresses.add(
                user.userId(),
                new NewAddress(b.label(), b.street(), b.unit(), b.city(), b.province(), b.postal(), b.note()));
    }

    @Operation(summary = "Rename an address, or change its unit or delivery note")
    @PatchMapping("/addresses/{addressId}")
    Address change(CurrentUser user, @PathVariable String addressId, @Valid @RequestBody AddressChangeRequest b) {
        return addresses.change(user.userId(), addressId, new AddressChange(b.label(), b.unit(), b.note()));
    }

    @Operation(summary = "Make an address the default")
    @PostMapping("/addresses/{addressId}/default")
    ListResponse<Address> makeDefault(CurrentUser user, @PathVariable String addressId) {
        return new ListResponse<>(addresses.makeDefault(user.userId(), addressId));
    }

    @Operation(summary = "Remove an address from the book")
    @DeleteMapping("/addresses/{addressId}")
    ListResponse<Address> remove(CurrentUser user, @PathVariable String addressId) {
        return new ListResponse<>(addresses.remove(user.userId(), addressId));
    }

    @Operation(summary = "The caller's household and Northline Plus")
    @GetMapping("/household")
    ResponseEntity<Household> household(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(households.household(user.userId()));
    }

    @Operation(summary = "Start the Northline Plus free trial for the caller's household")
    @PostMapping("/plus")
    Household startPlus(CurrentUser user, @Valid @RequestBody PlusRequest body) {
        return households.startPlus(user.userId(), body.plan());
    }

    @Operation(summary = "Cancel Northline Plus")
    @DeleteMapping("/plus")
    Household cancelPlus(CurrentUser user) {
        return households.cancelPlus(user.userId());
    }
}
