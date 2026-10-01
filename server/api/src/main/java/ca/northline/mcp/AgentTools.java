package ca.northline.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The operations agents get as MCP tools (S-127). Everything else in the api's OpenAPI document stays out — refunds,
 * payouts (sending money, schedules, bank accounts), checkout, deleting listings, team and security settings, API keys.
 * Each entry is an existing REST operation: the tool runs it with the caller's own token, so the operation's
 * {@code @RequiresMerchant} permission, {@code @PartnerAccess} scope and validation decide as for the Studio.
 *
 * <ul>
 *   <li>{@link Kind#READ} — scope {@code mcp} (partners: {@code api.read});
 *   <li>{@link Kind#WRITE} — scope {@code mcp.write} (partners: {@code api.write}); confirmed by a second identical call;
 *   <li>{@link Kind#OPS_READ} / {@link Kind#OPS_WRITE} — Northline staff: role {@code staff}, {@code acr=mfa} and scope {@code mcp.ops}; writes among
 *       them are confirmed too.
 * </ul>
 */
public final class AgentTools {

    public enum Kind {
        READ,
        WRITE,
        OPS_READ,
        OPS_WRITE;

        public boolean writes() {
            return this == WRITE || this == OPS_WRITE;
        }

        public boolean ops() {
            return this == OPS_READ || this == OPS_WRITE;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One tool: its name, the operation (method + OpenAPI path) it runs, and what an agent is told about it. */
    public record Tool(String name, String method, String path, Kind kind, String description) {}

    private static final String M = "/api/v1/merchants/{merchantId}";

    static final List<Tool> ALL = List.of(
            // who the caller acts for
            read(
                    "list_my_businesses",
                    "GET",
                    "/api/v1/me/businesses",
                    "The businesses you belong to, with your role in each. Start here: every other tool takes a merchantId."),
            read("get_business", "GET", M, "One business: name, type (provider, seller, kitchen), tier and status."),
            // listings
            read(
                    "search_listings",
                    "GET",
                    M + "/listings",
                    "The business's listings (services and products) with price, stock, sales in the last 30 days,"
                            + " vetting and live status. kind = service | product narrows it."),
            read("get_listing", "GET", M + "/listings/{listingId}", "One listing in full."),
            write(
                    "update_listing_price_stock",
                    "PATCH",
                    M + "/listings/{listingId}/price-stock",
                    "Change a listing's price (cents, CAD, tax excluded) and/or stock. A new price on an approved"
                            + " listing sends it back to vetting. Products with variants are changed in the Studio."),
            // orders and fulfilment
            read("list_orders", "GET", M + "/orders", "The business's shop orders, newest first."),
            read("get_order", "GET", M + "/orders/{orderId}", "One order with its lines and fulfilment state."),
            write(
                    "mark_order_packed",
                    "POST",
                    M + "/orders/{orderId}/pack",
                    "Mark a shop order packed: it is ready for the courier pick-up."),
            read(
                    "get_kitchen_board",
                    "GET",
                    M + "/kitchen/live",
                    "A kitchen's live orders: new, cooking and ready, with promised times."),
            write(
                    "kitchen_accept_order",
                    "POST",
                    M + "/kitchen/live/{orderId}/accept",
                    "Accept a food order and start cooking; the customer sees the promised time."),
            write(
                    "kitchen_mark_ready",
                    "POST",
                    M + "/kitchen/live/{orderId}/ready",
                    "Mark a food order ready for pick-up."),
            write(
                    "kitchen_hand_off",
                    "POST",
                    M + "/kitchen/live/{orderId}/handoff",
                    "Hand a food order to the courier or the customer. This releases the kitchen's escrow for it."),
            // bookings (appointments) and quotes
            read("list_appointments", "GET", M + "/jobs", "Booked jobs (appointments) of a service business."),
            read("get_appointment", "GET", M + "/jobs/{jobId}", "One booked job with its schedule and status."),
            write(
                    "appointment_en_route",
                    "POST",
                    M + "/jobs/{jobId}/en-route",
                    "Tell the customer the technician is on the way."),
            write("appointment_on_site", "POST", M + "/jobs/{jobId}/on-site", "Record arrival on site."),
            read("list_quote_requests", "GET", M + "/quote-requests", "Customers' requests for a quote."),
            // availability
            read("get_availability_hours", "GET", M + "/availability/hours", "Weekly opening / working hours."),
            write("set_availability_hours", "PUT", M + "/availability/hours", "Replace the weekly hours."),
            read(
                    "get_booking_rules",
                    "GET",
                    M + "/availability/rules",
                    "Booking rules: lead time, buffers, booking window."),
            read("list_time_off", "GET", M + "/availability/time-off", "Time off and closures."),
            write(
                    "add_time_off",
                    "POST",
                    M + "/availability/time-off",
                    "Add time off; bookings in it are listed by get_time_off_conflicts first."),
            read(
                    "get_time_off_conflicts",
                    "GET",
                    M + "/availability/time-off/conflicts",
                    "Bookings that a planned time off would clash with."),
            // messages
            read("list_message_threads", "GET", M + "/threads", "Conversations with customers, newest first."),
            read("get_message_thread", "GET", M + "/threads/{threadId}", "One conversation with its messages."),
            write(
                    "reply_to_thread",
                    "POST",
                    M + "/threads/{threadId}/messages",
                    "Send a reply in a conversation (text only through this tool)."),
            // money (read only)
            read("get_earnings", "GET", M + "/earnings", "Earnings: held in escrow, available, paid out."),
            read("get_payouts_overview", "GET", M + "/payouts/overview", "Balance, next payout and payout schedule."),
            read("list_payouts", "GET", M + "/payouts", "Past payouts."),
            // reviews
            read("get_review_summary", "GET", M + "/reviews/summary", "Average rating and counts."),
            read("list_reviews", "GET", M + "/reviews", "Customers' reviews."),
            // Northline staff
            new Tool(
                    "list_registry_reviews",
                    "GET",
                    "/api/v1/console/registry-reviews",
                    Kind.OPS_READ,
                    "Staff: business registry checks waiting for a manual decision."),
            new Tool(
                    "decide_registry_review",
                    "POST",
                    "/api/v1/console/registry-reviews/{id}/decision",
                    Kind.OPS_WRITE,
                    "Staff: record the decision on a registry check."));

    private AgentTools() {}

    public static List<Tool> all() {
        return ALL;
    }

    /** The tool behind an operation, if agents may use it. */
    public static Optional<Tool> forOperation(String method, String path) {
        return ALL.stream()
                .filter(t -> t.method().equalsIgnoreCase(method) && t.path().equals(path))
                .findFirst();
    }

    public static Optional<Tool> named(@Nullable String name) {
        return ALL.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    private static Tool read(String name, String method, String path, String description) {
        return new Tool(name, method, path, Kind.READ, description);
    }

    private static Tool write(String name, String method, String path, String description) {
        return new Tool(
                name,
                method,
                path,
                Kind.WRITE,
                description + " Changes data: call it once to see what will"
                        + " happen, then again with the same arguments to confirm.");
    }
}
