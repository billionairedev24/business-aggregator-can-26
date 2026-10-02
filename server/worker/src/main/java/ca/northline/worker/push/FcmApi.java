package ca.northline.worker.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * FCM HTTP v1 ({@code projects.messages.send}) and the OAuth 2.0 token exchange of a service account (Google's
 * "Using OAuth 2.0 for Server to Server Applications": a signed JWT assertion for an access token).
 */
@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
public interface FcmApi {

    /** {@code {"message": {...}}} → {@code {"name": "projects/<p>/messages/<id>"}}. */
    @PostExchange(url = "/v1/projects/{project}/messages:send", contentType = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> send(
            @PathVariable String project,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body);

    /** {@code grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<JWT>} at the key's token URI. */
    @PostExchange(contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    AccessToken token(URI tokenUri, @RequestBody MultiValueMap<String, String> form);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccessToken(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") @Nullable Long expiresIn) {}
}
