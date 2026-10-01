package ca.northline.payments.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.shared.security.MerchantPermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-130: the Studio assistant's money tools — read-only, over the Earnings and Payouts screens' use cases
 * ({@link ViewEarnings}, {@link ViewPayouts}), for roles with {@code FINANCE_READ} (owner, bookkeeper) only. No bank
 * account details and no customer names: amounts, states, dates and job/order refs.
 */
final class PaymentsAssistantTools {
    private PaymentsAssistantTools() {}

    static @Nullable String local(@Nullable Instant at, AssistantTool.Call call) {
        return at == null ? null : at.atZone(call.zone()).toOffsetDateTime().toString();
    }

    @Component
    @RequiredArgsConstructor
    static final class EarningsTool implements AssistantTool {
        private final ViewEarnings earnings;

        @Override
        public String name() {
            return "earnings_overview";
        }

        @Override
        public String description() {
            return "Earnings in CAD cents: releasing with the next payout, available, net in escrow (and how many jobs),"
                    + " on hold (disputes, refunds), next payout time, payout frequency, tier and take rate, plus the"
                    + " latest ledger lines (label, ref, gross, fee, net, state, release time).";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("lines", Map.of("type", "integer", "minimum", 0, "maximum", 30, "description", "Ledger lines (default 10)"));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.FINANCE_READ;
        }

        @Override
        public String screen() {
            return "earnings";
        }

        @Override
        public Result run(Call call) {
            var o = earnings.overview(call.merchantId());
            var out = new LinkedHashMap<String, Object>();
            out.put("releasingNextPayoutCents", o.headlineCents());
            out.put("availableCents", o.availableCents());
            out.put("escrowNetCents", o.escrowNetCents());
            out.put("escrowJobs", o.escrowCount());
            out.put("onHoldCents", o.onHoldCents());
            out.put("onHoldDisputes", o.onHoldDisputes());
            out.put("onHoldRefunds", o.onHoldRefunds());
            out.put("nextPayoutAt", local(o.nextPayoutAt(), call));
            out.put("payoutFrequency", o.frequency());
            out.put("tier", o.tier());
            out.put("takeRateBps", o.takeRateBps());
            out.put(
                    "ledger",
                    earnings.ledger(call.merchantId(), call.integer("lines", 10, 0, 30)).stream()
                            .map(l -> {
                                var m = new LinkedHashMap<String, Object>();
                                m.put("label", l.label());
                                m.put("ref", l.orderNumber());
                                m.put("kind", l.kind());
                                m.put("at", local(l.occurredAt(), call));
                                m.put("grossCents", l.grossCents());
                                m.put("feeCents", l.feeCents());
                                m.put("netCents", l.netCents());
                                m.put("state", l.state());
                                m.put("releaseAt", local(l.releaseAt(), call));
                                return m;
                            })
                            .toList());
            return new Result(out, "earnings → " + o.headlineCents() / 100 + " $ releasing");
        }
    }

    @Component
    @RequiredArgsConstructor
    static final class PayoutsTool implements AssistantTool {
        private final ViewPayouts payouts;

        @Override
        public String name() {
            return "payouts_overview";
        }

        @Override
        public String description() {
            return "Payouts in CAD cents: available now, payable (after the reserve), reserve, next payout time,"
                    + " schedule frequency, whether payouts are paused, and recent payouts (amount, state, arrival).";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of();
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.FINANCE_READ;
        }

        @Override
        public String screen() {
            return "payouts";
        }

        @Override
        public Result run(Call call) {
            var o = payouts.overview(call.merchantId());
            var out = new LinkedHashMap<String, Object>();
            out.put("availableCents", o.availableCents());
            out.put("payableCents", o.payableCents());
            out.put("reserveCents", o.reserveCents());
            out.put("nextPayoutAt", local(o.nextPayoutAt(), call));
            out.put("frequency", o.schedule().frequency());
            out.put("pausedUntil", local(o.pausedUntil(), call));
            out.put("bankAccountOnFile", o.account() != null);
            out.put(
                    "recent",
                    payouts.history(call.merchantId(), 5).stream()
                            .map(p -> Map.of(
                                    "amountCents", p.getAmountCents(),
                                    "kind", p.getKind(),
                                    "state", p.getState(),
                                    "arrivesAt", String.valueOf(local(p.getArrivesAt(), call))))
                            .toList());
            return new Result(out, "payouts → next " + String.valueOf(local(o.nextPayoutAt(), call)));
        }
    }
}
