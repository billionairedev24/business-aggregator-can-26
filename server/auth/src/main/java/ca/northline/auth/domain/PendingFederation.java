package ca.northline.auth.domain;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * A Google / Apple account the person just proved they hold, waiting in the auth session for the Northline side
 * (S-18): the second factor of the existing account it will be linked to ({@code userId}), or a new account
 * ({@code userId} null). {@code linked}: already linked earlier — only "last used" is recorded.
 */
public record PendingFederation(
        String provider,
        String subject,
        @Nullable String email,
        boolean privateRelay,
        @Nullable String userId,
        boolean linked)
        implements Serializable {}
