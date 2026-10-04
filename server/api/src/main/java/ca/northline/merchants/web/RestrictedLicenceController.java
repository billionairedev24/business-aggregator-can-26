package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.api.RestrictedLicences;
import ca.northline.merchants.api.RestrictedLicences.Licence;
import ca.northline.merchants.application.RestrictedLicenceUseCases.SubmitLicence;
import ca.northline.merchants.domain.LicenceRules;
import ca.northline.region.api.AgeClass;
import ca.northline.shared.Bytes;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Studio › Compliance › Age-restricted sales: the business's licences for alcohol, tobacco/vape and cannabis
 * accessories. The team reads them; owners submit one (a renewal is a new submission).
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/restricted-licences                         (VIEW)   {licensed, items}
 * POST /api/v1/merchants/{merchantId}/restricted-licences  multipart ageClass, licenceNumber, expiresOn, file (MANAGE)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/restricted-licences")
@RequiredArgsConstructor
class RestrictedLicenceController {

    private final RestrictedLicences licences;
    private final SubmitLicence submit;

    /** @param licensed the classes the business may sell now */
    record LicencesView(Set<AgeClass> licensed, List<Licence> items) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    LicencesView list(@PathVariable String merchantId) {
        return new LicencesView(licences.licensedClasses(merchantId), licences.of(merchantId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresMerchant(MANAGE)
    Licence submit(
            @PathVariable String merchantId,
            @RequestParam(name = "ageClass", required = false) @Nullable String ageClass,
            @RequestParam(name = "licenceNumber", required = false) @Nullable String licenceNumber,
            @RequestParam(name = "expiresOn", required = false) @Nullable String expiresOn,
            @RequestPart(name = "file", required = false) @Nullable MultipartFile file,
            CurrentMember member) {
        try {
            return submit.submit(new SubmitLicence.Command(
                    merchantId,
                    member.userId(),
                    member.role().code(),
                    ageClass,
                    licenceNumber,
                    date(expiresOn),
                    file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getContentType(),
                    file == null || file.isEmpty() ? null : Bytes.of(file.getBytes())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static @Nullable LocalDate date(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().toLowerCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            throw RuleViolation.of("expiresOn", "format", LicenceRules.EXPIRY);
        }
    }
}
