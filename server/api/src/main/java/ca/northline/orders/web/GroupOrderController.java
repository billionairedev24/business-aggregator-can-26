package ca.northline.orders.web;

import ca.northline.orders.application.FoodCheckout;
import ca.northline.orders.application.GroupOrders;
import ca.northline.orders.application.GroupOrders.GroupCart;
import ca.northline.orders.application.GroupOrders.GroupView;
import ca.northline.orders.application.GroupOrders.HostGroupOrder;
import ca.northline.orders.application.GroupOrders.JoinGroupOrder;
import ca.northline.orders.application.GroupOrders.PayGroupOrder;
import ca.northline.orders.application.GroupOrders.Preview;
import ca.northline.orders.application.GroupOrders.StartGroupOrder;
import ca.northline.orders.application.GroupOrders.ViewGroupOrder;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group food orders (S-159) for signed-in customers, on the consumer site and the app. Paying follows S-57's rules:
 * an {@code Idempotency-Key}, and a phone-code-only sign-in also needs a fresh step-up proof ({@code X-Step-Up}; 403
 * {@code step_up_required} / {@code second_factor_required}). Someone not in the group gets 404; a member asking for
 * the host's actions gets 403 {@code not_host}.
 *
 * <pre>
 * POST   /api/v1/me/group-orders                        {merchantId, mode, scheduledFor?, delivery?, payment: host|split} → 201
 * GET    /api/v1/me/group-orders                        {items: [GroupView]} the caller's unfinished groups
 * GET    /api/v1/me/group-orders/join/{code}            the join page: kitchen, host's first name, state
 * POST   /api/v1/me/group-orders/join                   {code} → the group (idempotent)
 * GET    /api/v1/me/group-orders/{id}                   the group: members, dishes, shares, tracking once placed
 * POST   /api/v1/me/group-orders/{id}/items             {itemId + optionIds | comboId + itemIds, qty, note?}
 * DELETE /api/v1/me/group-orders/{id}/items/{itemId}
 * POST   /api/v1/me/group-orders/{id}/leave
 * POST   /api/v1/me/group-orders/{id}/lock              host: {tip?} — split payment prices every share
 * POST   /api/v1/me/group-orders/{id}/unlock            host, while nobody has paid
 * POST   /api/v1/me/group-orders/{id}/cancel            host, before the kitchen has it (paid shares are released)
 * POST   /api/v1/me/group-orders/{id}/checkout          host pays all: S-57's card payment (201, Started)
 * POST   /api/v1/me/group-orders/{id}/confirm           host pays all: the card is authorized → placed
 * POST   /api/v1/me/group-orders/{id}/shares/{userId}/pay      split: one's own share, or the host for a guest (201)
 * POST   /api/v1/me/group-orders/{id}/shares/{userId}/confirm  split: the share's card is authorized
 * POST   /api/v1/me/group-orders/{id}/place             split: every share paid → one kitchen ticket
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/group-orders")
@RequiredArgsConstructor
class GroupOrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String STEP_UP = "X-Step-Up";

    private final StartGroupOrder starting;
    private final JoinGroupOrder joining;
    private final GroupCart cart;
    private final HostGroupOrder hosting;
    private final PayGroupOrder paying;
    private final ViewGroupOrder viewing;
    private final IdempotentRequests idempotent;

    record StartRequest(
            @Nullable String merchantId,
            @Nullable String mode,
            @Nullable Instant scheduledFor,
            FoodOrderController.@Nullable Delivery delivery,
            @Nullable String payment) {}

    record JoinRequest(@Nullable String code) {}

    record ItemRequest(
            @Nullable String itemId,
            @Nullable String comboId,
            @Nullable Integer qty,
            @Nullable List<String> optionIds,
            @Nullable List<String> itemIds,
            @Nullable String note) {}

    record TipRequest(FoodOrderController.@Nullable Tip tip) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupView start(@RequestBody StartRequest body, CurrentUser user) {
        var order = FoodOrderController.FoodRequests.order(new FoodOrderController.OrderRequest(
                body.merchantId(), body.mode(), body.scheduledFor(), null, null, null, body.delivery(), null, null));
        return starting.start(
                user.userId(),
                new GroupOrders.Start(
                        order.merchantId(),
                        order.mode(),
                        order.scheduledFor(),
                        order.delivery(),
                        Objects.requireNonNullElse(body.payment(), "")));
    }

    @GetMapping
    ListResponse<GroupView> active(CurrentUser user) {
        return new ListResponse<>(viewing.active(user.userId()));
    }

    @GetMapping("/join/{code}")
    Preview preview(@PathVariable String code, CurrentUser user) {
        return joining.preview(user.userId(), code);
    }

    @PostMapping("/join")
    GroupView join(@RequestBody JoinRequest body, CurrentUser user) {
        return joining.join(user.userId(), Objects.requireNonNullElse(body.code(), ""));
    }

    @GetMapping("/{groupId}")
    GroupView view(@PathVariable String groupId, CurrentUser user) {
        return viewing.view(user.userId(), groupId);
    }

    @PostMapping("/{groupId}/items")
    GroupView add(@PathVariable String groupId, @RequestBody ItemRequest body, CurrentUser user) {
        return cart.add(
                user.userId(),
                groupId,
                new GroupOrders.Item(
                        body.itemId(),
                        body.comboId(),
                        Objects.requireNonNullElse(body.qty(), 1),
                        Objects.requireNonNullElse(body.optionIds(), List.of()),
                        Objects.requireNonNullElse(body.itemIds(), List.of()),
                        body.note()));
    }

    @DeleteMapping("/{groupId}/items/{itemId}")
    GroupView remove(@PathVariable String groupId, @PathVariable String itemId, CurrentUser user) {
        return cart.remove(user.userId(), groupId, itemId);
    }

    @PostMapping("/{groupId}/leave")
    GroupView leave(@PathVariable String groupId, CurrentUser user) {
        return cart.leave(user.userId(), groupId);
    }

    @PostMapping("/{groupId}/lock")
    GroupView lock(
            @PathVariable String groupId, @RequestBody(required = false) @Nullable TipRequest body, CurrentUser user) {
        return hosting.lock(user.userId(), groupId, tip(body));
    }

    @PostMapping("/{groupId}/unlock")
    GroupView unlock(@PathVariable String groupId, CurrentUser user) {
        return hosting.unlock(user.userId(), groupId);
    }

    @PostMapping("/{groupId}/cancel")
    GroupView cancel(@PathVariable String groupId, CurrentUser user) {
        return hosting.cancel(user.userId(), groupId);
    }

    @PostMapping("/{groupId}/place")
    GroupView place(@PathVariable String groupId, CurrentUser user) {
        return hosting.place(user.userId(), groupId);
    }

    @PostMapping("/{groupId}/checkout")
    ResponseEntity<String> checkout(
            @PathVariable String groupId,
            @RequestBody(required = false) @Nullable TipRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestHeader(value = STEP_UP, required = false) @Nullable String stepUp,
            CurrentUser user,
            Locale locale) {
        var tip = body == null || body.tip() == null ? null : tip(body);
        return json(idempotent.run(
                "consumer:" + user.userId() + ":group-order:" + groupId,
                key,
                body,
                HttpStatus.CREATED.value(),
                () -> paying.checkout(
                        user.userId(), user.mfa(), stepUp, groupId, tip, Objects.requireNonNull(key), locale)));
    }

    @PostMapping("/{groupId}/confirm")
    ResponseEntity<String> confirm(
            @PathVariable String groupId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            CurrentUser user) {
        return json(idempotent.run(
                "consumer:" + user.userId() + ":group-order:" + groupId + ":confirm",
                key,
                null,
                HttpStatus.OK.value(),
                () -> paying.confirm(user.userId(), groupId)));
    }

    @PostMapping("/{groupId}/shares/{forUserId}/pay")
    ResponseEntity<String> payShare(
            @PathVariable String groupId,
            @PathVariable String forUserId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestHeader(value = STEP_UP, required = false) @Nullable String stepUp,
            CurrentUser user) {
        return json(idempotent.run(
                "consumer:" + user.userId() + ":group-share:" + groupId + ":" + forUserId,
                key,
                null,
                HttpStatus.CREATED.value(),
                () -> paying.payShare(
                        user.userId(), user.mfa(), stepUp, groupId, forUserId, Objects.requireNonNull(key))));
    }

    @PostMapping("/{groupId}/shares/{forUserId}/confirm")
    ResponseEntity<String> confirmShare(
            @PathVariable String groupId,
            @PathVariable String forUserId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            CurrentUser user) {
        return json(idempotent.run(
                "consumer:" + user.userId() + ":group-share:" + groupId + ":" + forUserId + ":confirm",
                key,
                null,
                HttpStatus.OK.value(),
                () -> paying.confirmShare(user.userId(), groupId, forUserId)));
    }

    private static FoodCheckout.Tip tip(@Nullable TipRequest body) {
        if (body == null || body.tip() == null) {
            return FoodCheckout.Tip.NONE;
        }
        return new FoodCheckout.Tip(
                Objects.requireNonNullElse(body.tip().kind(), "none"),
                body.tip().value());
    }

    private static ResponseEntity<String> json(IdempotentRequests.Outcome outcome) {
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
