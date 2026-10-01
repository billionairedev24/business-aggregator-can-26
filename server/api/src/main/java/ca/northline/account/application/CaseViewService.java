package ca.northline.account.application;

import ca.northline.account.application.Cases.Detail;
import ca.northline.account.application.Cases.Row;
import ca.northline.account.application.Cases.Step;
import ca.northline.account.application.Cases.ViewCases;
import ca.northline.account.application.ViewActivity.Item;
import ca.northline.messaging.api.CustomerCaseDesk;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.payments.api.CustomerCaseQuery;
import ca.northline.payments.api.CustomerCaseQuery.CaseSummary;
import ca.northline.payments.api.SavedCards;
import ca.northline.shared.NotFound;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Help &amp; cases (S-60): payments' cases with what they are about, and each case's timeline and conversation. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CaseViewService implements ViewCases {

    private final CustomerCaseQuery cases;
    private final CustomerCaseDesk desk;
    private final CustomerOrders orders;
    private final ViewActivity activity;
    private final SavedCards cards;
    private final Businesses businesses;

    @Override
    public List<Row> cases(String userId) {
        var list = cases.cases(userId, 200);
        var subjects = subjects(userId);
        var names = businesses.of(
                list.stream().map(CaseSummary::merchantId).distinct().toList());
        return list.stream()
                .map(c -> row(
                        c,
                        subjects,
                        names.containsKey(c.merchantId())
                                ? Objects.requireNonNull(names.get(c.merchantId()))
                                        .name()
                                : ""))
                .toList();
    }

    @Override
    public Detail detail(String userId, String caseId) {
        var c = cases.find(userId, caseId).orElseThrow(() -> new NotFound("case", caseId));
        var name = businesses.one(c.merchantId()).map(Businesses.Business::name).orElse("");
        var card = cards.defaultCard(userId)
                .map(x -> new Problems.Card(x.brand(), x.last4()))
                .orElse(null);
        return new Detail(
                row(c, subjects(userId), name),
                steps(c),
                card,
                desk.forRefund(userId, c.id()).orElse(null));
    }

    @Override
    @Transactional
    public Detail addNote(String userId, String caseId, String body, List<String> attachmentIds) {
        var thread = desk.forRefund(userId, caseId).orElseThrow(() -> new NotFound("case", caseId));
        desk.addNote(userId, thread.id(), body, attachmentIds);
        return detail(userId, caseId);
    }

    /** Orders & bookings rows by the escrow references their cases point at. */
    private Map<String, Item> subjects(String userId) {
        var items = activity.items(userId);
        var byRef = new HashMap<String, Item>();
        var lineOrders = new HashMap<String, String>();
        orders.recent(userId, ViewActivity.LIMIT).forEach(o -> o.lineIds().forEach(l -> lineOrders.put(l, o.id())));
        for (var item : items) {
            switch (item.kind()) {
                case FOOD -> byRef.put("food_order:" + item.id(), item);
                case BOOKING -> byRef.put("booking:" + item.id(), item);
                case ORDER ->
                    lineOrders.forEach((line, order) -> {
                        if (order.equals(item.id())) {
                            byRef.put("order_line:" + line, item);
                        }
                    });
                case QUOTE -> {}
            }
        }
        return byRef;
    }

    private static Row row(CaseSummary c, Map<String, Item> subjects, Map<String, Businesses.Business> names) {
        var name = names.containsKey(c.merchantId())
                ? Objects.requireNonNull(names.get(c.merchantId())).name()
                : "";
        return row(c, subjects, name);
    }

    private static Row row(CaseSummary c, Map<String, Item> subjects, String merchantName) {
        return new Row(
                c.id(),
                c.number(),
                c.kind(),
                c.state(),
                c.open(),
                c.what(),
                c.amountCents(),
                c.taxCents(),
                merchantName,
                c.openedAt(),
                c.respondBy(),
                c.outcome(),
                c.settledCents(),
                subjects.get(c.refType() + ":" + c.refId()));
    }

    /** Submitted → Seller reviews → Northline decides → Refund issued, from the case's state. */
    static List<Step> steps(CaseSummary c) {
        var steps = new ArrayList<Step>();
        steps.add(new Step("submitted", "done", c.openedAt()));
        var refund = "refund".equals(c.kind());
        String seller;
        String northline;
        String money;
        @Nullable Instant moneyAt = null;
        if (refund) {
            seller = "seller_review".equals(c.state()) ? "current" : "done";
            northline = switch (c.state()) {
                case "agent_review" -> "current";
                case "seller_review" -> "todo";
                default -> c.agentDecided() ? "done" : "skipped";
            };
            money = switch (c.state()) {
                case "paid" -> "done";
                case "approved" -> "current";
                case "denied" -> "denied";
                default -> "todo";
            };
            moneyAt = c.paidAt();
        } else {
            seller = "open".equals(c.state()) ? "current" : "done";
            northline = switch (c.state()) {
                case "agent", "appealed" -> "current";
                case "open", "seller_replied" -> "todo";
                default -> "done";
            };
            var settled = c.settledCents() != null && c.settledCents() > 0;
            money = "decided".equals(c.state()) ? (settled ? "done" : "denied") : "todo";
            moneyAt = c.decidedAt();
        }
        steps.add(new Step("seller", seller, c.reviewBy()));
        steps.add(new Step("northline", northline, "done".equals(northline) ? c.decidedAt() : null));
        steps.add(new Step("refund", money, "done".equals(money) ? moneyAt : null));
        return steps;
    }
}
