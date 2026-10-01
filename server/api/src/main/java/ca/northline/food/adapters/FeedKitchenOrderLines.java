package ca.northline.food.adapters;

import ca.northline.food.api.KitchenOrderFeed;
import ca.northline.food.application.KitchenOrderLines;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link KitchenOrderLines} from the orders module's {@link KitchenOrderFeed} (S-64). */
@Component
@RequiredArgsConstructor
class FeedKitchenOrderLines implements KitchenOrderLines {

    private final KitchenOrderFeed orders;

    @Override
    public List<String> lineIds(String merchantId, String orderId) {
        return orders.lines(merchantId, List.of(orderId)).stream()
                .map(KitchenOrderFeed.Line::id)
                .toList();
    }
}
