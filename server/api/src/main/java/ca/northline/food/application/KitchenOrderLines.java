package ca.northline.food.application;

import java.util.List;

/** Outbound port: the ids of this kitchen's lines in an order (escrow is held per order line). */
public interface KitchenOrderLines {
    List<String> lineIds(String merchantId, String orderId);
}
