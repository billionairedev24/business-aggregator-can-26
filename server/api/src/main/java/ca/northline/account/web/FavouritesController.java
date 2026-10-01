package ca.northline.account.web;

import ca.northline.account.application.Favourites.Favourite;
import ca.northline.account.application.Favourites.ManageFavourites;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Favourite providers &amp; shops (S-58):
 *
 * <pre>
 * GET    /api/v1/me/favourites                newest first, with what the caller did with each business
 * PUT    /api/v1/me/favourites/{businessId}   add (idempotent; 404 when the business isn't active)
 * DELETE /api/v1/me/favourites/{businessId}   remove (idempotent)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/favourites")
@RequiredArgsConstructor
class FavouritesController {

    private final ManageFavourites favourites;

    @Operation(summary = "The caller's favourite providers and shops")
    @GetMapping
    ResponseEntity<ListResponse<Favourite>> list(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(favourites.list(user.userId())));
    }

    @Operation(summary = "Add a business to the caller's favourites")
    @PutMapping("/{businessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(CurrentUser user, @PathVariable String businessId) {
        favourites.add(user.userId(), businessId);
    }

    @Operation(summary = "Remove a business from the caller's favourites")
    @DeleteMapping("/{businessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(CurrentUser user, @PathVariable String businessId) {
        favourites.remove(user.userId(), businessId);
    }
}
