package ca.northline.messaging.application;

import ca.northline.messaging.domain.InboxScope;
import ca.northline.shared.NavBadgeContributor;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sidebar badges: {@code messages} = threads the member can see with unread customer or Northline messages ("3");
 * {@code help} = the business's open cases ("1 open" · "1 ouvert").
 */
@Component
@RequiredArgsConstructor
class MessagingNavBadges implements NavBadgeContributor {

    private final ThreadStore threads;
    private final HelpCaseStore cases;

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> badges(Context context) {
        var badges = new LinkedHashMap<String, String>();
        int unread = threads.unreadThreads(context.merchantId(), InboxScope.of(context.role()), context.userId());
        if (unread > 0) {
            badges.put("messages", Integer.toString(unread));
        }
        int open = cases.openCount(context.merchantId());
        if (open > 0) {
            badges.put("help", openLabel(open, context.french()));
        }
        return badges;
    }

    static String openLabel(int count, boolean french) {
        if (french) {
            return count == 1 ? "1 ouvert" : count + " ouverts";
        }
        return count + " open";
    }
}
