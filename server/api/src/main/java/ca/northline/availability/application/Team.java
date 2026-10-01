package ca.northline.availability.application;

import ca.northline.availability.application.AvailabilityUseCases.Member;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.security.MerchantRole;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Team members who can take jobs (everyone but bookkeepers), with their names; and where the business is (S-134): its
 * hours, days, cut-offs and holidays are local to its market's time zone and its province's holiday calendar.
 */
@Component
@RequiredArgsConstructor
class Team {

    private final TeamRoster roster;
    private final PersonDirectory people;
    private final MerchantPlaces places;

    /** The business's time zone (its market's, else its province's; region model). */
    ZoneId zone(String merchantId) {
        return places.of(merchantId).zone();
    }

    MerchantPlaces.MerchantPlace place(String merchantId) {
        return places.of(merchantId);
    }

    List<Member> members(String merchantId) {
        var team = roster.members(merchantId).stream()
                .filter(m -> m.role() != MerchantRole.BOOKKEEPER)
                .toList();
        var names =
                people.people(team.stream().map(TeamRoster.TeamMember::userId).toList());
        return team.stream()
                .map(m -> new Member(
                        m.userId(),
                        names.containsKey(m.userId()) ? names.get(m.userId()).displayName() : m.userId(),
                        m.role(),
                        m.bookable()))
                .toList();
    }

    Optional<Member> member(String merchantId, String userId) {
        return members(merchantId).stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst();
    }
}
