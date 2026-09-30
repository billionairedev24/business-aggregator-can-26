package ca.northline.food.application;

import ca.northline.food.application.PosImportViews.Applied;
import ca.northline.food.application.PosImportViews.ConnectStart;
import ca.northline.food.application.PosImportViews.ConnectionView;
import ca.northline.food.application.PosImportViews.Preview;
import ca.northline.food.domain.PosProvider;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** S-36 "Import from POS": connect a POS, preview what an import would change, apply it. */
public final class PosImportUseCases {
    private PosImportUseCases() {}

    public interface ManagePosConnections {
        List<ConnectionView> connections(String merchantId);

        /**
         * OAuth POSes: stores a single-use state and returns the consent page ({@code menuId}: where the Studio returns).
         * Toast: links {@code restaurantId} (422 when Northline can't read it).
         */
        ConnectStart connect(
                String merchantId,
                String userId,
                PosProvider provider,
                @Nullable String menuId,
                @Nullable String restaurantId);

        ConnectionView disconnect(String merchantId, PosProvider provider);
    }

    public interface ImportFromPos {
        /** Reads the POS menu and stores a preview with the diff against what earlier imports created. */
        Preview preview(String merchantId, String menuId, PosProvider provider, String actorId);

        Preview view(String merchantId, String importId);

        /** Applies a preview (409 when it was applied, discarded or is older than an hour). */
        Applied apply(String merchantId, String importId);

        Preview discard(String merchantId, String importId);
    }
}
