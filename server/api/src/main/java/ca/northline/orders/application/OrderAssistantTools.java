package ca.northline.orders.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.orders.application.OrderUseCases.ListOrders;
import ca.northline.orders.application.OrderUseCases.PackOrder;
import ca.northline.orders.application.OrderViews.OrderSummary;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.MerchantPermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-130: the Studio assistant's order tools, over the Orders screen's own use cases ({@link ListOrders},
 * {@link PackOrder}). Orders carry refs, items, totals, states and run times — no customer name or address.
 */
final class OrderAssistantTools {
    private OrderAssistantTools() {}

    static @Nullable String local(@Nullable Instant at, AssistantTool.Call call) {
        return at == null ? null : at.atZone(call.zone()).toOffsetDateTime().toString();
    }

    static Map<String, Object> compact(OrderSummary o, AssistantTool.Call call) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ref", o.ref() == null ? o.id() : o.ref());
        m.put("status", o.status());
        m.put("items", o.lines().stream().map(l -> l.title() + " ×" + l.qty()).toList());
        m.put("totalCents", o.totalCents());
        m.put("run", o.runLabel());
        m.put("cutoff", local(o.cutoffAt(), call));
        m.put("placed", local(o.placedAt(), call));
        if (o.issueNote() != null) {
            m.put("issue", o.issueNote());
        }
        return m;
    }

    @Component
    @RequiredArgsConstructor
    static final class ListOrdersTool implements AssistantTool {
        private final ListOrders orders;

        @Override
        public String name() {
            return "list_orders";
        }

        @Override
        public String description() {
            return "The business's shop orders: open ones plus today's deliveries and issues, with counts per status"
                    + " (to_pack, awaiting_pickup, out_for_delivery, delivered, issue), items, totals and run cut-offs.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of(
                    "status",
                    Map.of(
                            "type", "string",
                            "description", "Only orders in this status",
                            "enum", List.of("to_pack", "awaiting_pickup", "out_for_delivery", "delivered", "issue")));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "orders";
        }

        @Override
        public Result run(Call call) {
            var board = orders.board(call.merchantId());
            var status = call.text("status");
            var rows = board.orders().stream()
                    .filter(o -> status == null || o.status().code().equals(status))
                    .limit(40)
                    .map(o -> compact(o, call))
                    .toList();
            var counts = board.counts();
            return new Result(
                    Map.of(
                            "counts",
                            Map.of(
                                    "toPack", counts.toPack(),
                                    "awaitingPickup", counts.awaitingPickup(),
                                    "deliveredToday", counts.deliveredToday(),
                                    "issues", counts.issues()),
                            "nextCutoff",
                            String.valueOf(local(counts.nextCutoff(), call)),
                            "orders",
                            rows),
                    "orders → " + rows.size() + (status == null ? "" : " " + status));
        }
    }

    /** "Mark packed" — a write: the assistant only proposes it; it runs when the person confirms. */
    @Component
    @RequiredArgsConstructor
    static final class PackOrderTool implements AssistantTool {
        private final ListOrders orders;
        private final PackOrder pack;

        @Override
        public String name() {
            return "pack_order";
        }

        @Override
        public String description() {
            return "Propose marking one order as packed (to_pack → awaiting_pickup). The person must confirm it.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("ref", Map.of("type", "string", "description", "The order ref, e.g. NL-48213"));
        }

        @Override
        public List<String> required() {
            return List.of("ref");
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.OPERATE;
        }

        @Override
        public String screen() {
            return "orders";
        }

        @Override
        public boolean write() {
            return true;
        }

        @Override
        public String preview(Call call) {
            var ref = String.valueOf(call.text("ref"));
            return call.locale().getLanguage().equals("fr")
                    ? "Marquer la commande " + ref + " comme emballée"
                    : "Mark order " + ref + " as packed";
        }

        @Override
        public Result run(Call call) {
            var ref = String.valueOf(call.text("ref")).strip();
            var order = orders.board(call.merchantId()).orders().stream()
                    .filter(o -> ref.equalsIgnoreCase(o.ref()) || ref.equals(o.id()))
                    .findFirst()
                    .orElseThrow(() -> new NotFound("order", ref));
            var packed = pack.pack(call.merchantId(), order.id(), call.userId());
            return new Result(compact(packed, call), "packed " + ref.toUpperCase(Locale.ROOT));
        }
    }
}
