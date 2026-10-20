package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.booking.api.CustomerPhotos;
import ca.northline.booking.application.ViewCustomerPhoto;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-153: a customer's photo on a quote request sent to the business, or on one of its bookings (Studio quote-request
 * card and job card). Anything else is 404, never a hint that the photo exists.
 */
@RestController
@RequiredArgsConstructor
class CustomerPhotoController {

    private final ViewCustomerPhoto photos;

    @GetMapping("/api/v1/merchants/{merchantId}/customer-photos/{photoId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> photo(@PathVariable String merchantId, @PathVariable String photoId) {
        return inline(photos.forMerchant(merchantId, photoId).orElseThrow(() -> new NotFound("photo", photoId)));
    }

    /** The photo inline, never cached by a shared cache (it is someone's home or car). */
    static ResponseEntity<byte[]> inline(CustomerPhotos.Content c) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(c.fileName())
                                .build()
                                .toString())
                .body(c.bytes().toArray());
    }
}
