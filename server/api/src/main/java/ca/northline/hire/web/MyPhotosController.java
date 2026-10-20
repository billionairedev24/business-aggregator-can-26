package ca.northline.hire.web;

import ca.northline.booking.api.CustomerPhotos;
import ca.northline.shared.Bytes;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * S-153: photos for a quote request or a booking's job details. Upload first ({@code multipart/form-data}, field
 * {@code file}; JPEG, PNG, HEIC or WebP up to 10 MB), then send the ids with the request ({@code photoIds}) or the
 * booking checkout. Only the owner reads them here.
 */
@RestController
@RequiredArgsConstructor
class MyPhotosController {

    private final CustomerPhotos photos;

    @Operation(summary = "Upload a photo for a quote request or a booking")
    @PostMapping(path = "/api/v1/me/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CustomerPhotos.Photo upload(CurrentUser user, @RequestParam("file") MultipartFile file) throws IOException {
        return photos.upload(
                user.userId(),
                Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                Bytes.of(file.getBytes()));
    }

    @Operation(summary = "The caller's own photo")
    @GetMapping("/api/v1/me/photos/{photoId}")
    ResponseEntity<byte[]> photo(CurrentUser user, @PathVariable String photoId) {
        var c = photos.content(user.userId(), photoId).orElseThrow(() -> new NotFound("photo", photoId));
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
