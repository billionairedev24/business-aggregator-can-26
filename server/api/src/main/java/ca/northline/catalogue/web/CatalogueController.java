package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.BrowseCategories;
import ca.northline.catalogue.application.LookupCatalogue;
import ca.northline.catalogue.application.ManageMedia;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.web.ListingResponses.CatalogMatchResponse;
import ca.northline.catalogue.web.ListingResponses.CategoryResponse;
import ca.northline.catalogue.web.ListingResponses.MediaResponse;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.RequiresMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Editor support: categories (cascading dropdowns), the GTIN lookup, image upload and image bytes. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class CatalogueController {

    private final BrowseCategories browseCategories;
    private final LookupCatalogue lookupCatalogue;
    private final ManageMedia manageMedia;
    private final ListingWebMapper mapper;

    @GetMapping("/catalogue/categories")
    @RequiresMerchant(VIEW)
    ListResponse<CategoryResponse> categories(
            @PathVariable String merchantId, @RequestParam String root, Locale locale) {
        if (!List.of("shop", "service").contains(root)) {
            throw RuleViolation.of("root", "format", "Use shop or service.");
        }
        return new ListResponse<>(mapper.toCategories(browseCategories.categories(root, locale)));
    }

    @GetMapping("/catalogue/products/lookup")
    @RequiresMerchant(VIEW)
    CatalogMatchResponse lookup(@PathVariable String merchantId, @RequestParam String gtin) {
        return lookupCatalogue
                .byGtin(gtin)
                .map(m -> mapper.toResponse(m, merchantId))
                .orElseThrow(() -> new NotFound("catalogue product", gtin));
    }

    @PostMapping(path = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    MediaResponse upload(@PathVariable String merchantId, @RequestPart("file") @Nullable MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", ListingMessages.IMAGE_REQUIRED);
        }
        try {
            return mapper.media(manageMedia.upload(merchantId, file.getBytes()), merchantId);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping("/media/{mediaId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> content(@PathVariable String merchantId, @PathVariable String mediaId) {
        var content = manageMedia.content(mediaId).orElseThrow(() -> new NotFound("media", mediaId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(content.bytes());
    }
}
