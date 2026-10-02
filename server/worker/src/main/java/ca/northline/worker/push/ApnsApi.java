package ca.northline.worker.push;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * APNs' one call (Apple, "Sending notification requests to APNs"): {@code POST /3/device/<device token>} over HTTP/2
 * with {@code authorization: bearer <provider JWT>}, {@code apns-topic}, {@code apns-push-type} and the JSON payload.
 * 200 = accepted; an error answers {@code {"reason": "BadDeviceToken"}} (410 adds {@code timestamp}).
 */
@HttpExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
public interface ApnsApi {

    @PostExchange("/3/device/{token}")
    ResponseEntity<Void> send(
            @PathVariable String token,
            @RequestHeader MultiValueMap<String, String> headers,
            @RequestBody String payload);
}
