package ca.northline.availability.application;

import ca.northline.availability.application.AvailabilityUseCases.Member;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.security.MerchantRole;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Team members who can take jobs (everyone but bookkeepers), with their names. */
@Component
@RequiredArgsConstructor
class Team {

    /** Studio time zone: hours, days and holidays are local to Calgary. */
    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    private final TeamRoster roster;
    private final PersonDirectory people;

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
