package ca.northline.shared.web;

import ca.northline.platform.CardData;
import ca.northline.shared.RuleViolation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-110 (PCI DSS SAQ A): card data never enters Northline. Cards are typed only into Stripe's fields (Payment Element,
 * PaymentSheet), so a JSON request body that carries a card number, magnetic-stripe track data, a verification code
 * next to its name, or a field named like one ({@code cardNumber}, {@code cvc}, {@code pan}) is someone pasting a card
 * into a free-text field (a message, a booking note, an AI prompt) or a client bug. It is refused with 422
 * {@code card_data} before the handler runs, so it is never stored, sent to another processor or echoed back.
 *
 * <p>The log line names the handler, the field and the masked number ({@code [CARD …4242]}), never the value — and the
 * S-112 Redactor would mask it again. Fields that legitimately hold long digit runs that may pass the Luhn check
 * (barcodes: {@code gtin}, {@code ean}, {@code upc}, {@code isbn}, {@code barcode}, {@code sku}) are not scanned for
 * numbers. Only bodies read by the JSON converter are checked: the Stripe webhooks read raw bytes (their signature
 * covers them) and Stripe never sends a full card number.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
class CardDataGuard extends RequestBodyAdviceAdapter {

    static final String RULE = "card_data";
    static final String MESSAGE = "Card numbers can't be sent here. Enter card details only in the secure card form.";

    private static final Set<String> BARCODE_FIELDS = Set.of("gtin", "ean", "upc", "isbn", "barcode", "sku");

    private final JsonMapper json;

    @Override
    public boolean supports(
            MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body;
        try (var in = input.getBody()) {
            body = in.readAllBytes();
        }
        findCardData(body).ifPresent(found -> {
            log.warn(
                    "Refused card data in a request body: {}.{} field {} ({})",
                    parameter.getContainingClass().getSimpleName(),
                    parameter.getExecutable().getName(),
                    found.field(),
                    found.what());
            throw RuleViolation.of(found.field(), RULE, MESSAGE);
        });
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    /** Where card data sits in a JSON body (the field path as the 422 names fields) and what it is, masked. */
    record Found(String field, String what) {}

    Optional<Found> findCardData(byte[] body) {
        if (body.length == 0) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty(); // the converter answers the malformed body itself
        }
        return find(root, "", "");
    }

    private static Optional<Found> find(JsonNode node, String path, String name) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                var key = entry.getKey();
                var child = entry.getValue();
                var childPath = path.isEmpty() ? key : path + "." + key;
                if (CardData.isCardDataName(key) && !blank(child)) {
                    return Optional.of(new Found(childPath, "a field named " + key));
                }
                var found = find(child, childPath, key);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (var i = 0; i < node.size(); i++) {
                var found = find(node.get(i), path + "[" + i + "]", name);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (!(node.isString() || node.isIntegralNumber())) {
            return Optional.empty();
        }
        var text = node.asString();
        var field = path.isEmpty() ? "body" : path;
        if (!BARCODE_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
            var pans = CardData.findPans(text);
            if (!pans.isEmpty()) {
                return Optional.of(new Found(field, pans.getFirst().masked()));
            }
        }
        if (CardData.containsTrackData(text)) {
            return Optional.of(new Found(field, "track data"));
        }
        if (CardData.containsVerificationCode(text)) {
            return Optional.of(new Found(field, "a card verification code"));
        }
        return Optional.empty();
    }

    private static boolean blank(JsonNode node) {
        return node.isNull()
                || node.isMissingNode()
                || (node.isString() && node.asString().isBlank());
    }
}
