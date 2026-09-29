package ca.northline.platform;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link MissingEnvironmentException} into Spring Boot's "APPLICATION FAILED TO START" report. */
public final class MissingEnvironmentFailureAnalyzer extends AbstractFailureAnalyzer<MissingEnvironmentException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingEnvironmentException cause) {
        var profile = cause.profiles().stream()
                .filter(p -> p.equals("dev") || p.equals("staging") || p.equals("prod"))
                .findFirst()
                .orElse("README");
        var description = "The active profiles %s need environment variables that are not set:%n  %s"
                .formatted(cause.profiles(), MissingEnvironmentException.listing(cause.missing(), "%n  ".formatted()));
        var action = ("Set these variables (a secrets manager or the deployment's environment in the cloud; a .env file"
                        + " next to the app for a local run). docs/runbooks/%s.md lists every variable, whether it"
                        + " is required and where its value comes from.")
                .formatted(profile);
        return new FailureAnalysis(description, action, cause);
    }
}
