package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.DELETE;
import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.MenuUseCases.EditMenuItems;
import ca.northline.food.application.MenuUseCases.MenuItemPhotos;
import ca.northline.food.application.MenuViews.ItemView;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.web.KitchenRequests.MenuItemRequest;
import ca.northline.food.web.KitchenRequests.SoldOutRequest;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Menu items: editor, "Sold out today", photo, delete. {@code POST} is the onboarding contract. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/menu-items")
@RequiredArgsConstructor
class MenuItemController {

    private final EditMenuItems items;
    private final MenuItemPhotos photos;

    @PostMapping
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    ItemView create(@PathVariable String merchantId, @Valid @RequestBody MenuItemRequest body) {
        return items.create(merchantId, body.toCommand());
    }

    @PutMapping("/{itemId}")
    @RequiresMerchant(EDIT)
    ItemView update(
            @PathVariable String merchantId, @PathVariable String itemId, @Valid @RequestBody MenuItemRequest body) {
        return items.update(merchantId, itemId, body.toCommand());
    }

    /** Cooks can sell out an item mid-service. */
    @PostMapping("/{itemId}/sold-out")
    @RequiresMerchant(EDIT)
    ItemView soldOut(
            @PathVariable String merchantId, @PathVariable String itemId, @Valid @RequestBody SoldOutRequest body) {
        return items.soldOut(merchantId, itemId, body.soldOut());
    }

    /** S-67: "Keep this price" — confirms a price the ±40 % check flagged, so the dish can go live. */
    @PostMapping("/{itemId}/confirm-price")
    @RequiresMerchant(EDIT)
    ItemView confirmPrice(@PathVariable String merchantId, @PathVariable String itemId) {
        return items.confirmPrice(merchantId, itemId);
    }

    @DeleteMapping("/{itemId}")
    @RequiresMerchant(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String merchantId, @PathVariable String itemId) {
        items.delete(merchantId, itemId);
    }

    /** Multipart {@code file}: JPEG / PNG / WebP ≤ 10 MB, ≥ 1000 px on the short side. */
    @PostMapping("/{itemId}/photo")
    @RequiresMerchant(EDIT)
    ItemView upload(
            @PathVariable String merchantId,
            @PathVariable String itemId,
            @RequestPart("file") @Nullable MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", KitchenMessages.PHOTO_FILE);
        }
        return photos.upload(
                merchantId, itemId, file.getBytes(), Objects.requireNonNullElse(file.getContentType(), ""));
    }

    @GetMapping("/{itemId}/photo")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> photo(@PathVariable String merchantId, @PathVariable String itemId) {
        return photos.photo(merchantId, itemId)
                .map(p -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(p.contentType()))
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .body(p.bytes().toArray()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
