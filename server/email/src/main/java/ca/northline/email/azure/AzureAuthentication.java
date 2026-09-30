package ca.northline.email.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** How requests to Azure Communication Services are authenticated: an access key (HMAC) or Entra ID (a bearer token). */
sealed interface AzureAuthentication extends ClientHttpRequestInterceptor {

    /**
     * Access-key authentication (the resource's "Keys" blade): every request is signed with HMAC-SHA256 over
     * {@code VERB\npath?query\nx-ms-date;host;x-ms-content-sha256}.
     */
    record AccessKey(SecretKeySpec key, Clock clock) implements AzureAuthentication {

        static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

        static AccessKey of(String base64Key, Clock clock) {
            return new AccessKey(new SecretKeySpec(Base64.getDecoder().decode(base64Key.strip()), "HmacSHA256"), clock);
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws java.io.IOException {
            var date = RFC_1123.format(clock.instant());
            var contentHash = Base64.getEncoder().encodeToString(sha256(body));
            var uri = request.getURI();
            var pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            var host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            var toSign =
                    request.getMethod().name() + "\n" + pathAndQuery + "\n" + date + ";" + host + ";" + contentHash;
            request.getHeaders().set("x-ms-date", date);
            request.getHeaders().set("x-ms-content-sha256", contentHash);
            request.getHeaders()
                    .set(
                            "Authorization",
                            "HMAC-SHA256 SignedHeaders=x-ms-date;host;x-ms-content-sha256&Signature=" + sign(toSign));
            return execution.execute(request, body);
        }

        String sign(String toSign) {
            try {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] sha256(byte[] body) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(body);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Entra ID (workload identity / managed identity): role "Communication and Email Service Owner" or a custom one. */
    record EntraId(TokenCredential credential) implements AzureAuthentication {

        static final TokenRequestContext SCOPE =
                new TokenRequestContext().addScopes("https://communication.azure.com/.default");

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws java.io.IOException {
            request.getHeaders().setBearerAuth(credential.getTokenSync(SCOPE).getToken());
            return execution.execute(request, body);
        }
    }
}
