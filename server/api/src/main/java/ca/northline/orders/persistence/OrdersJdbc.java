package ca.northline.orders.persistence;

import ca.northline.orders.api.OfferSales;
import ca.northline.orders.api.OrderInsights;
import ca.northline.orders.application.OrderQueries;
import ca.northline.orders.application.OrderViews.Line;
import ca.northline.orders.domain.MerchantOrder;
import ca.northline.orders.domain.OrderEnums.LineState;
import ca.northline.orders.domain.OrderEnums.OrderState;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.NavBadgeContributor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The seller's view of {@code orders.*}: only lines with {@code merchant_id} = the seller are read or written. Also
 * the public {@link OrderInsights} and the Orders sidebar badge ("4 to pack").
 */
@Repository
@RequiredArgsConstructor
class OrdersJdbc implements OrderQueries, OrderInsights, OfferSales, NavBadgeContributor {

    private static final String ORDER_COLUMNS = """
            o.id, o.ref, o.customer_id, o.delivery_area, o.state, o.placed_at, o.delivered_at,
            w.starts_at as window_starts_at, w.cutoff_at, w.run_label
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public List<OrderRow> board(String merchantId, Instant since) {
        var headers = jdbc.sql("select " + ORDER_COLUMNS + """
                          from orders.orders o left join orders.delivery_windows w on w.id = o.window_id
                         where exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                           and (o.state in ('placed', 'accepted', 'packing', 'ready', 'picked_up')
                                or o.delivered_at >= :since
                                or (o.placed_at >= :issuesSince and exists (
                                      select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m
                                         and (l.state in ('short', 'refunded') or l.issue_note is not null))))
                         order by w.starts_at nulls last, o.id
                        """)
                .param("m", merchantId)
                .param("since", JdbcTimes.ts(since))
                .param("issuesSince", JdbcTimes.ts(since.minus(Duration.ofDays(14))))
                .query((rs, _) -> header(rs))
                .list();
        return withLines(merchantId, headers);
    }

    @Override
    public Optional<OrderRow> row(String merchantId, String orderId) {
        var headers = jdbc.sql("select " + ORDER_COLUMNS + """
                          from orders.orders o left join orders.delivery_windows w on w.id = o.window_id
                         where o.id = :id
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                        """)
                .param("m", merchantId)
                .param("id", orderId)
                .query((rs, _) -> header(rs))
                .list();
        return withLines(merchantId, headers).stream().findFirst();
    }

    @Override
    public Optional<MerchantOrder> packing(String merchantId, String orderId) {
        return row(merchantId, orderId)
                .map(r -> MerchantOrder.builder()
                        .orderId(r.id())
                        .merchantId(merchantId)
                        .orderState(r.state())
                        .lines(r.lines().stream()
                                .map(l -> new MerchantOrder.Line(l.id(), l.state(), null))
                                .toList())
                        .othersPending(jdbc.sql("""
                                select exists (select 1 from orders.order_lines
                                                where order_id = :id and merchant_id <> :m and state = 'pending')
                                """)
                                .param("id", orderId)
                                .param("m", merchantId)
                                .query(Boolean.class)
                                .single())
                        .build());
    }

    @Override
    public void savePacking(MerchantOrder order, String actorId, Instant at) {
        for (var line : order.getLines()) {
            jdbc.sql("""
                            update orders.order_lines set state = :state,
                                   packed_at = case when :state = 'packed' and packed_at is null then :at else packed_at end,
                                   packed_by = case when :state = 'packed' and packed_by is null then :by else packed_by end
                             where id = :id and merchant_id = :m
                            """)
                    .param("state", line.state().code())
                    .param("at", JdbcTimes.ts(at))
                    .param("by", actorId)
                    .param("id", line.id())
                    .param("m", order.getMerchantId())
                    .update();
        }
        jdbc.sql("update orders.orders set state = :state where id = :id")
                .param("state", order.getOrderState().code())
                .param("id", order.getOrderId())
                .update();
    }

    // ── OrderInsights ───────────────────────────────────────────────────────────

    @Override
    public Packing packing(String merchantId, Instant now) {
        return jdbc.sql("""
                        select count(distinct o.id) as to_pack,
                               min(w.cutoff_at) filter (where w.cutoff_at > :now) as cutoff,
                               (array_agg(w.run_label order by w.cutoff_at) filter (where w.cutoff_at > :now))[1] as run
                          from orders.orders o
                          join orders.order_lines l on l.order_id = o.id and l.merchant_id = :m and l.state = 'pending'
                          left join orders.delivery_windows w on w.id = o.window_id
                         where o.state in ('placed', 'accepted', 'packing')
                        """)
                .param("m", merchantId)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) ->
                        new Packing(rs.getInt("to_pack"), JdbcTimes.instant(rs, "cutoff"), rs.getString("run")))
                .single();
    }

    @Override
    public List<RunOrder> run(String merchantId, Instant from, Instant to) {
        var headers = jdbc.sql("select " + ORDER_COLUMNS + """
                          from orders.orders o join orders.delivery_windows w on w.id = o.window_id
                         where w.starts_at >= :from and w.starts_at < :to
                           and o.state in ('placed', 'accepted', 'packing', 'ready')
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                         order by w.starts_at, o.id
                        """)
                .param("m", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> header(rs))
                .list();
        return withLines(merchantId, headers).stream()
                .map(r -> new RunOrder(
                        r.id(),
                        r.ref(),
                        r.runLabel(),
                        r.customerId(),
                        r.area(),
                        r.lines().stream()
                                .map(l -> new Item(l.title(), l.qty()))
                                .toList(),
                        r.lines().stream().noneMatch(l -> l.state() == LineState.PENDING)))
                .sorted(java.util.Comparator.comparing(RunOrder::packed))
                .toList();
    }

    @Override
    public Volume volume(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(distinct o.id) as orders, coalesce(sum(l.qty), 0) as items
                          from orders.orders o join orders.order_lines l on l.order_id = o.id and l.merchant_id = :m
                         where o.placed_at >= :from and o.placed_at < :to and o.state <> 'cancelled'
                        """)
                .param("m", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new Volume(rs.getLong("orders"), rs.getLong("items")))
                .single();
    }

    @Override
    public Map<String, Long> unitsByOffer(String merchantId, Instant from, Instant to) {
        return jdbc
                .sql("""
                        select l.offer_id, sum(coalesce(l.qty, 1)) as units
                          from orders.order_lines l join orders.orders o on o.id = l.order_id
                         where l.merchant_id = :m and l.offer_id is not null
                           and coalesce(l.state, 'pending') <> 'refunded'
                           and o.type = 'goods' and o.state not in ('cancelled', 'refunded')
                           and o.placed_at >= :from and o.placed_at < :to
                         group by l.offer_id
                        """)
                .param("m", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> Map.entry(rs.getString("offer_id"), rs.getLong("units")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** "4 to pack" / « 4 à emballer ». */
    @Override
    public Map<String, String> badges(NavBadgeContributor.Context context) {
        var merchantId = context.merchantId();
        var locale = context.locale();
        int toPack = packing(merchantId, clock.instant()).toPack();
        if (toPack == 0) {
            return Map.of();
        }
        var text = "fr".equals(locale.getLanguage()) ? "%d à emballer" : "%d to pack";
        return Map.of("orders", text.formatted(toPack));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private record Header(
            String id,
            @Nullable String ref,
            @Nullable String customerId,
            @Nullable String area,
            OrderState state,
            @Nullable Instant placedAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant windowStartsAt,
            @Nullable Instant cutoffAt,
            @Nullable String runLabel) {}

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("customer_id"),
                rs.getString("delivery_area"),
                CodedEnum.fromCode(OrderState.class, rs.getString("state")),
                JdbcTimes.instant(rs, "placed_at"),
                JdbcTimes.instant(rs, "delivered_at"),
                JdbcTimes.instant(rs, "window_starts_at"),
                JdbcTimes.instant(rs, "cutoff_at"),
                rs.getString("run_label"));
    }

    private record LineRow(
            String orderId, Line line, @Nullable String issueNote) {}

    private List<OrderRow> withLines(String merchantId, List<Header> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        Collection<String> ids = headers.stream().map(Header::id).toList();
        var lines = jdbc.sql("""
                        select id, order_id, coalesce(title, 'Item') as title, coalesce(qty, 1) as qty,
                               coalesce(unit_cents, 0) as unit_cents, coalesce(state, 'pending') as state, issue_note
                          from orders.order_lines where merchant_id = :m and order_id in (:ids) order by order_id, id
                        """)
                .param("m", merchantId)
                .param("ids", ids)
                .query((rs, _) -> new LineRow(
                        rs.getString("order_id"),
                        new Line(
                                rs.getString("id"),
                                rs.getString("title"),
                                rs.getInt("qty"),
                                rs.getLong("unit_cents"),
                                CodedEnum.fromCode(LineState.class, rs.getString("state"))),
                        rs.getString("issue_note")))
                .list();
        var byOrder = new LinkedHashMap<String, List<LineRow>>();
        lines.forEach(l ->
                byOrder.computeIfAbsent(l.orderId(), _ -> new ArrayList<>()).add(l));
        return headers.stream()
                .map(h -> {
                    var own = byOrder.getOrDefault(h.id(), List.of());
                    return new OrderRow(
                            h.id(),
                            h.ref(),
                            h.customerId(),
                            h.area(),
                            h.state(),
                            own.stream().map(LineRow::line).toList(),
                            own.stream()
                                    .map(LineRow::issueNote)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(null),
                            h.windowStartsAt(),
                            h.cutoffAt(),
                            h.runLabel(),
                            h.placedAt(),
                            h.deliveredAt());
                })
                .toList();
    }
}
