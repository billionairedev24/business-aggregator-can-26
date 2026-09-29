package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.payments.domain.Zones;
import ca.northline.shared.NavBadgeContributor;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance sidebar badges (design 02 nav): {@code payouts} = the next payout day ("Fri" — a date for daily / monthly
 * schedules; none for manual or while payouts are paused), {@code refunds} = cases waiting for the merchant.
 */
@Component
@RequiredArgsConstructor
class PaymentsNavBadges implements NavBadgeContributor {

    private final PayoutRepository payouts;
    private final CaseRepository cases;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> badges(Context context) {
        var merchantId = context.merchantId();
        var locale = context.locale();
        var badges = new LinkedHashMap<String, String>();
        var schedule = payouts.schedule(merchantId).orElse(PayoutSchedule.DEFAULT);
        if (payouts.pendingAccount(merchantId).isEmpty()) {
            schedule.nextAfter(clock.instant(), null).ifPresent(next -> {
                var day = next.atZone(Zones.EDMONTON);
                badges.put(
                        "payouts",
                        schedule.frequency() == PayoutSchedule.Frequency.WEEKLY
                                ? day.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale)
                                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                        .withLocale(locale)
                                        .format(day)
                                        .replaceAll(",? \\d{4}$", ""));
            });
        }
        var waiting = cases.awaitingMerchant(merchantId);
        if (waiting > 0) {
            badges.put("refunds", String.valueOf(waiting));
        }
        return badges;
    }
}
