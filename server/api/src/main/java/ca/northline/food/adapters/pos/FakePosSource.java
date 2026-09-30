package ca.northline.food.adapters.pos;

import ca.northline.food.application.PosMenuSource;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.Ids;
import ca.northline.shared.integration.ProviderHttp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code northline.pos.provider=local} (the {@code local} and {@code test} default; refused under staging/prod): Square,
 * Clover and Toast without accounts. "Connect" goes straight back to the callback with a fake code (Toast accepts any
 * GUID except the nil one); the menu is {@code pos-fixtures/menu.json} (Pho Dau Bo), whose "Soup of the day" has no
 * price (a problem the preview lists). Edit a dish in Northline and import again to see a re-import diff.
 */
class FakePosSource implements PosMenuSource {

    static final String NIL = "00000000-0000-0000-0000-000000000000";

    private final PosProvider provider;
    private final PosMenu fixture;

    FakePosSource(PosProvider provider) {
        this.provider = provider;
        try (var in = new ClassPathResource("pos-fixtures/menu.json").getInputStream()) {
            this.fixture = ProviderHttp.JSON.readValue(in, PosMenu.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public PosProvider provider() {
        return provider;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri) {
        var p = ProviderHttp.params();
        p.put("code", "fake-" + Ids.next().toLowerCase(Locale.ROOT));
        p.put("state", state);
        if (provider == PosProvider.CLOVER) {
            p.put("merchant_id", "FAKECLOVER01");
        }
        return URI.create(redirectUri + ProviderHttp.query(p));
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri) {
        if (!params.getOrDefault("code", "").startsWith("fake-")) {
            throw new GrantRevoked("invalid_grant");
        }
        var account = provider == PosProvider.CLOVER
                ? params.getOrDefault("merchant_id", "FAKECLOVER01")
                : "FAKE-SQUARE-KITCHEN";
        return new Grant(
                new Credentials("fake-access-" + Ids.next(), "fake-refresh-" + Ids.next(), null, account),
                account,
                "Pho Dau Bo (" + (provider == PosProvider.CLOVER ? "Clover" : "Square") + ", local fake)");
    }

    @Override
    public Grant link(String restaurantId) {
        if (NIL.equals(restaurantId)) {
            throw new Unverified("integration not enabled");
        }
        return new Grant(new Credentials("", null, null, restaurantId), restaurantId, "Pho Dau Bo (Toast, local fake)");
    }

    @Override
    public Credentials refresh(Credentials credentials) {
        if ("fake-refresh-revoked".equals(credentials.refreshToken())) {
            throw new GrantRevoked("invalid_grant");
        }
        return credentials;
    }

    @Override
    public PosMenu menu(Credentials credentials) {
        return fixture;
    }

    @Override
    public void revoke(Credentials credentials) {}
}
