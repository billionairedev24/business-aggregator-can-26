package ca.northline.merchants.integration;

import ca.northline.merchants.application.TeamInviteSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@link TeamInviteSender} adapters. No production email/SMS provider is chosen yet (docs/DECISIONS.md). */
final class TeamInviteSenders {
    private TeamInviteSenders() {}

    /** Local development and tests: the link is logged (the owner also sees it in the invite dialog). */
    @Slf4j
    @Component
    @Profile({"local", "test"})
    static class LoggingTeamInviteSender implements TeamInviteSender {
        @Override
        public void send(Invite invite) {
            log.info(
                    "Team invitation to {} ({}): {} invites you to join {} as {} → {}",
                    invite.contact().channel(),
                    invite.contact().email() != null
                            ? invite.contact().email()
                            : invite.contact().phone(),
                    invite.inviterName(),
                    invite.businessName(),
                    invite.role().code(),
                    invite.link());
        }
    }

    /** Other profiles until a provider is wired: nothing is sent; the owner shares the link from the dialog. */
    @Component
    @Profile("!local & !test")
    static class UnconfiguredTeamInviteSender implements TeamInviteSender {
        @Override
        public void send(Invite invite) {
            throw new IllegalStateException("No email/SMS provider is configured for team invitations");
        }
    }
}
