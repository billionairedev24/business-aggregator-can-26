package ca.northline.privacy.application;

import ca.northline.privacy.domain.PrivacyRules.Correction;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a request keeps of the person's own words, sealed ({@code privacy.requests.sealed_*}, bound to the request id):
 * their contact when they asked (to find rows that kept it after the account is blanked) and the corrections they
 * asked for. Wiped when the request closes.
 */
@Component
@RequiredArgsConstructor
class Secrets {

    private final SecretSealer sealer;
    private final JsonMapper json;

    record Content(
            @Nullable String email,
            @Nullable String phone,
            List<Correction> corrections,
            @Nullable String note) {

        Content {
            corrections = List.copyOf(corrections);
        }
    }

    Sealed seal(String requestId, Content content) {
        return sealer.seal(json.writeValueAsString(content), context(requestId));
    }

    Content open(String requestId, @Nullable Sealed sealed) {
        if (sealed == null) {
            return new Content(null, null, List.of(), null);
        }
        return json.readValue(sealer.open(sealed, context(requestId)), Content.class);
    }

    private static String context(String requestId) {
        return PrivacyRequestStore.SEALED_CONTEXT + requestId;
    }
}
