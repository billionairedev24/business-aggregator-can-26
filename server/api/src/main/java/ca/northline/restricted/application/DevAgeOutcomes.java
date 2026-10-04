package ca.northline.restricted.application;

import java.util.List;
import java.util.Optional;

/** The local fake identity provider's outcome page ({@code local} profile; also tests): pick how a session ends. */
public interface DevAgeOutcomes {

    List<String> outcomes();

    /** Applies the outcome as the provider's webhook would; returns the session's return URL. */
    Optional<String> finish(String sessionId, String outcome);
}
