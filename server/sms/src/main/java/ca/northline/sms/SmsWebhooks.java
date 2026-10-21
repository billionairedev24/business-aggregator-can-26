package ca.northline.sms;

import ca.northline.platform.SmsProperties;
import ca.northline.sms.twilio.TwilioSignature;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * S-157: how a webhook from the SMS provider (an inbound text, a delivery status) is authenticated, by {@code
 * northline.sms.provider}: {@code twilio} — {@link TwilioSignature} with {@code SMS_AUTH_TOKEN} over the public URL;
 * {@code local} — the developer fake: accepted unsigned, and only under the {@code local} and {@code test} profiles (the
 * {@code curl} in docs/runbooks/casl.md); {@code aws} / {@code azure} — none yet (their inbound messages arrive through
 * SNS / Event Grid, not built): empty, and the endpoint answers 503.
 */
public final class SmsWebhooks {

    /** Checks one delivery; {@code url} is the public URL the provider was given. */
    public interface Verifier {
        String provider();

        boolean verified(String url, Map<String, List<String>> params, @Nullable String signature);
    }

    private SmsWebhooks() {}

    public static Optional<Verifier> of(SmsProperties sms, Environment environment) {
        return switch (sms.provider()) {
            case TWILIO -> {
                var token = sms.authToken();
                if (!StringUtils.hasText(token)) {
                    throw new IllegalStateException("SMS_PROVIDER=twilio needs SMS_AUTH_TOKEN to verify its webhooks");
                }
                yield Optional.of(new Verifier() {
                    @Override
                    public String provider() {
                        return "twilio";
                    }

                    @Override
                    public boolean verified(String url, Map<String, List<String>> params, @Nullable String signature) {
                        return TwilioSignature.valid(token.strip(), url, params, signature);
                    }
                });
            }
            case LOCAL ->
                environment.matchesProfiles("local | test")
                        ? Optional.of(new Verifier() {
                            @Override
                            public String provider() {
                                return "local";
                            }

                            @Override
                            public boolean verified(
                                    String url, Map<String, List<String>> params, @Nullable String signature) {
                                return true;
                            }
                        })
                        : Optional.empty();
            case AWS, AZURE -> Optional.empty();
        };
    }
}
