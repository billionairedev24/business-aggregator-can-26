package ca.northline.studio.application;

import ca.northline.shared.NavBadgeContributor;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Merges every {@link NavBadgeContributor}. A contributor that fails is logged and skipped (badges are decoration;
 * the shell must still load); blank texts are dropped; if two modules claim the same screen, the first bean wins.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class NavBadgeService implements NavBadges {

    private final List<NavBadgeContributor> contributors;

    @Override
    public Map<String, String> of(NavBadgeContributor.Context context) {
        var out = new TreeMap<String, String>();
        for (var contributor : contributors) {
            Map<String, String> badges;
            try {
                badges = contributor.badges(context);
            } catch (RuntimeException ex) {
                log.warn(
                        "Nav badge contributor {} failed for {}",
                        contributor.getClass().getName(),
                        context.merchantId(),
                        ex);
                continue;
            }
            badges.forEach((screen, text) -> {
                if (text == null || text.isBlank()) {
                    return;
                }
                var previous = out.putIfAbsent(screen, text);
                if (previous != null && !previous.equals(text)) {
                    log.warn("Two nav badge contributors claim screen '{}'; keeping '{}'", screen, previous);
                }
            });
        }
        return out;
    }
}
