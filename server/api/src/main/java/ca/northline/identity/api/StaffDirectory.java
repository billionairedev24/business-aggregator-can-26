package ca.northline.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Northline staff and their console roles ({@code identity.platform_roles}) for the console's Team screen (S-96), which
 * replaces granting by SQL. The console checks who may change roles and audits; this reads and writes the rows. A
 * change reaches the person's access token at its next refresh (10 minutes).
 */
public interface StaffDirectory {

    /** Everyone holding a platform role, by name. */
    List<Member> members();

    Optional<Member> member(String userId);

    /** An active account by its email (case-insensitive), for "Invite". */
    Optional<Member> byEmail(String email);

    /** Adds the role (and {@code staff}, which opens the console); false when already held. */
    boolean grant(String userId, String role, String grantedBy);

    /** Removes the role; with no console role left, {@code staff} goes too. False when not held. */
    boolean revoke(String userId, String role);

    /**
     * @param roles the platform roles held ({@code staff} included), sorted
     * @param grantedAt when the first role was granted, null for someone holding none yet
     */
    record Member(
            String id,
            String name,
            @Nullable String email,
            List<String> roles,
            @Nullable Instant grantedAt) {

        public Member {
            roles = List.copyOf(roles);
        }
    }
}
