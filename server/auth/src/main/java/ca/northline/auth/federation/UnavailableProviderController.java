package ca.northline.auth.federation;

import ca.northline.auth.application.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * "Continue with Google/Apple" for a provider this environment has no client registration for (S-18): back to the
 * Studio's sign-in page with {@code error=federation_unavailable} instead of an error page. Configured providers never
 * get here — Spring's authorization redirect filter answers them first.
 */
@Controller
@RequiredArgsConstructor
class UnavailableProviderController {

    private final AuthProperties props;

    @GetMapping(FederationConfig.AUTHORIZATION_BASE + "/{provider}")
    RedirectView unavailable(@PathVariable String provider) {
        var view = new RedirectView(UriComponentsBuilder.fromUriString(props.loginPage())
                .queryParam("error", "federation_unavailable")
                .toUriString());
        view.setStatusCode(HttpStatus.FOUND);
        return view;
    }
}
