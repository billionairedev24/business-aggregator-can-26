package ca.northline.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-62: which sign-in page a browser is sent to. Two web apps sign people in against this server's JSON API — the
 * Studio ({@code northline.auth.login-page}) and the consumer site ({@code northline.auth.consumer-login-page}); S-90 adds
 * the platform console ({@code northline.auth.console-login-page}) for staff. An authorization request goes to its
 * client's page; a Google / Apple sign-in comes back to the app that started it
 * (the consumer's buttons add {@code ?app=consumer}, carried through the provider in the {@code state}).
 */
@Component
public class LoginPages {

    /** Prefix of the federation {@code state} when the sign-in started on the consumer site. */
    public static final String CONSUMER_STATE_PREFIX = "consumer.";

    public static final String CONSUMER_APP = "consumer";

    private final AuthProperties props;

    public LoginPages(AuthProperties props) {
        this.props = props;
    }

    public String studio() {
        return props.loginPage();
    }

    public String consumer() {
        var page = props.consumerLoginPage();
        return page == null || page.isBlank() ? props.loginPage() : page;
    }

    /** S-90: the platform console's sign-in page (staff). */
    public String console() {
        var page = props.consoleLoginPage();
        return page == null || page.isBlank() ? props.loginPage() : page;
    }

    /** The sign-in page of an OAuth client's people. */
    public String forClient(@Nullable String clientId) {
        if (clientId != null && props.consumerClients().contains(clientId)) {
            return consumer();
        }
        return clientId != null && props.consoleClients().contains(clientId) ? console() : studio();
    }

    /** {@code ?app=consumer} on a request (the consumer's Google / Apple buttons). */
    public static boolean fromConsumer(HttpServletRequest request) {
        return CONSUMER_APP.equals(request.getParameter("app"));
    }

    /** Back from Google / Apple: the page of the app that started it (from the callback's {@code state}). */
    public String forFederationCallback(HttpServletRequest request) {
        var state = request.getParameter("state");
        return state != null && state.startsWith(CONSUMER_STATE_PREFIX) ? consumer() : studio();
    }

    /** The registration page next to a sign-in page ({@code …/sign-in} → {@code …/register}). */
    public static String registerPage(String signInPage) {
        return signInPage.replace("/sign-in", "/register");
    }

    /**
     * Whether {@code clientId} may only get a code after a second factor. S-127: agents registered by a Client ID
     * Metadata Document ({@code client_id} = an HTTPS URL) act for a business, so they need one too.
     */
    public boolean requiresMfa(@Nullable String clientId) {
        return clientId != null
                && (props.mfaRequiredClients().contains(clientId)
                        || clientId.startsWith("https://")
                        || clientId.startsWith("http://"));
    }
}
