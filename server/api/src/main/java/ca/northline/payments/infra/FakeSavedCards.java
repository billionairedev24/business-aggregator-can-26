package ca.northline.payments.infra;

import ca.northline.payments.application.SavedCardGateway;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Saved cards without Stripe (local, test, CI): a SetupIntent succeeds as soon as it is read and leaves a test Visa
 * ending 4242 (expiring three years out) on the customer. Kept in memory — a restart forgets the cards, and nothing
 * here ever sees a card number.
 */
@RequiredArgsConstructor
class FakeSavedCards implements SavedCardGateway {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, SetupIntent> intents = new ConcurrentHashMap<>();
    private final Map<String, List<Card>> cards = new ConcurrentHashMap<>();
    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    private String id(String prefix) {
        var sb = new StringBuilder(prefix);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Override
    public SetupIntent createSetupIntent(String stripeCustomer, String idempotencyKey) {
        var seti = id("seti_");
        var intent = new SetupIntent(seti, "requires_payment_method", seti + "_secret_" + id(""), stripeCustomer, null);
        intents.put(seti, intent);
        return intent;
    }

    @Override
    public SetupIntent setupIntent(String setupIntentId) {
        return intents.compute(setupIntentId, (id, intent) -> {
            if (intent == null) {
                return new SetupIntent(id, "canceled", null, null, null);
            }
            if (intent.succeeded() || intent.customer() == null) {
                return intent;
            }
            var pm = id("pm_");
            var year = clock.instant().atZone(ZoneOffset.UTC).getYear() + 3;
            cards.computeIfAbsent(intent.customer(), _ -> new ArrayList<>())
                    .add(new Card(pm, "visa", "4242", 12, year, clock.instant()));
            return new SetupIntent(id, "succeeded", intent.clientSecret(), intent.customer(), pm);
        });
    }

    @Override
    public List<Card> cards(String stripeCustomer) {
        return cards.getOrDefault(stripeCustomer, List.of()).stream()
                .sorted(Comparator.comparing(Card::created).reversed())
                .toList();
    }

    @Override
    public @Nullable String defaultCard(String stripeCustomer) {
        return defaults.get(stripeCustomer);
    }

    @Override
    public void makeDefault(String stripeCustomer, String paymentMethod, String idempotencyKey) {
        defaults.put(stripeCustomer, paymentMethod);
    }

    @Override
    public void detach(String paymentMethod, String idempotencyKey) {
        cards.values().forEach(list -> list.removeIf(c -> c.paymentMethod().equals(paymentMethod)));
        defaults.values().removeIf(paymentMethod::equals);
    }
}
