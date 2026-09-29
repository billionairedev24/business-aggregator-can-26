/**
 * Identity: the person behind a login ({@code identity.users}) — profile reads for the api. Accounts are created and
 * authenticated by northline-auth (server/auth), which shares the database and owns the {@code auth} schema; this module
 * reads {@code identity.users} and will own profile edits (see DECISIONS.md, Auth workstream).
 */
@ApplicationModule(displayName = "identity")
@NullMarked
package ca.northline.identity;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
