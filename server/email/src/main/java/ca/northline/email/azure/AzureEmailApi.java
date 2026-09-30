package ca.northline.email.azure;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Azure Communication Services Email REST API — {@code POST /emails:send?api-version=2023-03-31} (202 Accepted with an
 * {@code Operation-Location} to poll; we don't poll: acceptance is what every provider adapter reports).
 */
public interface AzureEmailApi {

    String API_VERSION = "2023-03-31";

    @PostExchange("/emails:send")
    ResponseEntity<Void> send(@RequestParam("api-version") String apiVersion, @RequestBody Email email);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Email(
            String senderAddress,
            Content content,
            Recipients recipients,
            @Nullable List<Address> replyTo,
            Map<String, String> headers,
            boolean userEngagementTrackingDisabled) {}

    record Content(String subject, String plainText, String html) {}

    record Recipients(List<Address> to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String address, @Nullable String displayName) {}
}
