package ca.northline.catalogue.adapters.commerce;

import static ca.northline.catalogue.adapters.commerce.CommerceHttp.JSON;
import static ca.northline.catalogue.adapters.commerce.CommerceHttp.text;

import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;

/**
 * {@code northline.commerce.provider=local} (the {@code local} and {@code test} default; refused under staging/prod):
 * Shopify, Square and Lightspeed without accounts. "Connect" goes straight back to the callback with a fake code; the
 * catalogue is the fixture in {@code commerce-fixtures/<platform>.json} (Prairie Wrench Parts' goods, one product
 * whose title has a promo word so the "couldn't import" list shows); stock moves by the hour (fixture + hour mod 3) so
 * the hourly sync visibly updates it. Images come from {@code fixtures.northline.invalid}, drawn by the local image
 * fetcher. Webhooks are not registered (the laptop polls) but the fake verifies {@code X-Fake-Signature} (base64
 * HMAC-SHA256 of the body with {@link #WEBHOOK_SECRET}) to try the webhook path by hand.
 */
class FakeCatalogSource implements CommerceCatalogSource {

    static final String WEBHOOK_SECRET = "local-commerce-webhook-secret";
    static final String IMAGE_HOST = "fixtures.northline.invalid";

    private final CommerceProvider provider;
    private final Clock clock;
    private final List<ExternalProduct> fixture;

    FakeCatalogSource(CommerceProvider provider, Clock clock) {
        this.provider = provider;
        this.clock = clock;
        this.fixture = load(provider);
    }

    @Override
    public CommerceProvider provider() {
        return provider;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri, @Nullable String shop) {
        var p = CommerceHttp.params();
        p.put("code", "fake-" + Ids.next().toLowerCase(Locale.ROOT));
        p.put("state", state);
        if (provider == CommerceProvider.SHOPIFY) {
            p.put("shop", shop == null ? "prairie-parts.myshopify.com" : shop);
        } else if (provider == CommerceProvider.LIGHTSPEED) {
            p.put("domain_prefix", "prairieparts");
        }
        return URI.create(redirectUri + CommerceHttp.query(p));
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri, @Nullable String shop) {
        var code = params.getOrDefault("code", "");
        if (!code.startsWith("fake-")) {
            throw new GrantRevoked("invalid_grant");
        }
        var account = switch (provider) {
            case SHOPIFY -> shop == null ? "prairie-parts.myshopify.com" : shop;
            case SQUARE -> "FAKE-SQUARE-MERCHANT";
            case LIGHTSPEED -> params.getOrDefault("domain_prefix", "prairieparts");
        };
        var label = switch (provider) {
            case SHOPIFY -> account;
            case SQUARE -> "Prairie Wrench Parts (Square, local fake)";
            case LIGHTSPEED -> account + ".retail.lightspeed.app";
        };
        return new Grant(
                new Credentials("fake-access-" + Ids.next(), "fake-refresh-" + Ids.next(), null, account),
                account,
                label,
                Set.of("read"));
    }

    @Override
    public Credentials refresh(Credentials credentials) {
        if ("fake-refresh-revoked".equals(credentials.refreshToken())) {
            throw new GrantRevoked("invalid_grant");
        }
        return credentials;
    }

    @Override
    public Page products(Credentials credentials, @Nullable String cursor) {
        return new Page(fixture.stream().map(this::withHourlyStock).toList(), null);
    }

    @Override
    public Optional<ExternalProduct> product(Credentials credentials, String externalId) {
        return fixture.stream()
                .filter(p -> p.id().equals(externalId))
                .findFirst()
                .map(this::withHourlyStock);
    }

    private ExternalProduct withHourlyStock(ExternalProduct p) {
        var bump = (int) (clock.millis() / Duration.ofHours(1).toMillis() % 3);
        return new ExternalProduct(
                p.id(),
                p.title(),
                p.description(),
                p.vendor(),
                p.images(),
                p.variants().stream()
                        .map(v -> new ExternalVariant(
                                v.id(),
                                v.sku(),
                                v.barcode(),
                                v.title(),
                                v.options(),
                                v.priceCents(),
                                v.stock() + bump,
                                v.stockRef()))
                        .toList(),
                p.updatedAt());
    }

    @Override
    public boolean subscribe(Credentials credentials, URI callbackUrl) {
        return false;
    }

    @Override
    public void revoke(Credentials credentials) {}

    @Override
    public Delivery verify(WebhookRequest request) {
        var expected = Base64.getEncoder().encodeToString(CommerceHttp.hmacSha256(WEBHOOK_SECRET, request.body()));
        if (!CommerceHttp.same(request.header("x-fake-signature"), expected)) {
            throw new Unverified("signature");
        }
        var body = JSON.readTree(request.body());
        var account = text(body, "account");
        var delivery = text(body, "deliveryId");
        if (account == null || delivery == null) {
            throw new Unverified("payload");
        }
        var id = String.valueOf(text(body, "id"));
        List<Change> changes = switch (String.valueOf(text(body, "type"))) {
            case "changed" -> List.of(new Change.ProductChanged(id));
            case "removed" -> List.of(new Change.ProductRemoved(id));
            case "stock" -> List.of(new Change.StockChanged(id));
            case "catalog" -> List.of(new Change.CatalogChanged());
            case "uninstalled" -> List.of(new Change.Uninstalled());
            default -> List.of();
        };
        return new Delivery(account, delivery, changes);
    }

    private static List<ExternalProduct> load(CommerceProvider provider) {
        JsonNode root;
        try (var in = new ClassPathResource("commerce-fixtures/" + provider.code() + ".json").getInputStream()) {
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var out = new ArrayList<ExternalProduct>();
        for (var p : root) {
            var images = new ArrayList<URI>();
            p.path("images")
                    .forEach(i -> images.add(URI.create("https://" + IMAGE_HOST + "/commerce/" + i.asString())));
            var variants = new ArrayList<ExternalVariant>();
            for (var v : p.path("variants")) {
                var options = new LinkedHashMap<String, String>();
                v.path("options")
                        .properties()
                        .forEach(e -> options.put(e.getKey(), e.getValue().asString()));
                var id = String.valueOf(text(v, "id"));
                variants.add(new ExternalVariant(
                        id,
                        text(v, "sku"),
                        text(v, "barcode"),
                        String.valueOf(text(v, "title")),
                        options,
                        v.path("priceCents").asLong(),
                        v.path("stock").asInt(),
                        id));
            }
            out.add(new ExternalProduct(
                    String.valueOf(text(p, "id")),
                    String.valueOf(text(p, "title")),
                    text(p, "description"),
                    text(p, "vendor"),
                    images,
                    variants,
                    null));
        }
        return List.copyOf(out);
    }
}
