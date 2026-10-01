package ca.northline.payments.application;

import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.SavedCards;
import ca.northline.payments.application.PaymentMethods.ManagePaymentMethods;
import ca.northline.payments.application.PaymentMethods.Payment;
import ca.northline.payments.application.PaymentMethods.SavedCard;
import ca.northline.payments.application.PaymentMethods.SavedCardStore;
import ca.northline.payments.application.PaymentMethods.Setup;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved cards through Stripe SetupIntents (S-59). Stripe is the source of truth: every list reads the customer's cards
 * from Stripe and refreshes the summary Northline keeps for the account menu (brand, last four, expiry, default).
 * A person without a Stripe Customer gets one on their first "Add payment method" (the same one checkout uses, S-11).
 */
@Service
@RequiredArgsConstructor
@Transactional
class SavedCardService implements ManagePaymentMethods, SavedCards {

    private final SavedCardGateway cards;
    private final PaymentGateway payments;
    private final EscrowRepository customers;
    private final SavedCardStore store;
    private final PaymentSettings settings;

    @Override
    public List<SavedCard> cards(String userId) {
        return customers.stripeCustomer(userId).map(c -> refresh(userId, c)).orElse(List.of());
    }

    @Override
    public Setup startSetup(String userId) {
        var customer = customers.stripeCustomer(userId).orElseGet(() -> {
            var created = payments.customer(userId, StripeIdempotencyKeys.of("customer", userId));
            customers.saveStripeCustomer(userId, created);
            return created;
        });
        var intent = cards.createSetupIntent(customer, StripeIdempotencyKeys.of("setup", userId, Ids.next()));
        return new Setup(intent.id(), intent.clientSecret(), settings.provider(), settings.publishableKey());
    }

    @Override
    public List<SavedCard> confirmSetup(String userId, String setupIntentId) {
        var customer = customers.stripeCustomer(userId).orElseThrow(() -> new NotFound("setup_intent", setupIntentId));
        var intent = cards.setupIntent(setupIntentId);
        if (!customer.equals(intent.customer())) {
            throw new NotFound("setup_intent", setupIntentId);
        }
        if (!intent.succeeded() || intent.paymentMethod() == null) {
            throw new Conflict("setup_incomplete", PaymentMethods.NOT_CONFIRMED);
        }
        if (cards.defaultCard(customer) == null) {
            cards.makeDefault(
                    customer,
                    Objects.requireNonNull(intent.paymentMethod()),
                    StripeIdempotencyKeys.of("default", setupIntentId));
        }
        return refresh(userId, customer);
    }

    @Override
    public List<SavedCard> makeDefault(String userId, String paymentMethod) {
        var customer = owned(userId, paymentMethod);
        cards.makeDefault(customer, paymentMethod, StripeIdempotencyKeys.of("default", userId, Ids.next()));
        return refresh(userId, customer);
    }

    @Override
    public List<SavedCard> remove(String userId, String paymentMethod) {
        var customer = owned(userId, paymentMethod);
        var wasDefault = paymentMethod.equals(cards.defaultCard(customer));
        cards.detach(paymentMethod, StripeIdempotencyKeys.of("detach", paymentMethod));
        if (wasDefault) {
            cards.cards(customer).stream()
                    .findFirst()
                    .ifPresent(next -> cards.makeDefault(
                            customer, next.paymentMethod(), StripeIdempotencyKeys.of("default", userId, Ids.next())));
        }
        return refresh(userId, customer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> billing(String userId, int limit) {
        return store.payments(userId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CardLabel> defaultCard(String customerId) {
        return store.cards(customerId).stream()
                .filter(SavedCard::isDefault)
                .findFirst()
                .map(c -> new CardLabel(c.brand(), c.last4()));
    }

    /** The customer's Stripe Customer when the card is theirs; 404 otherwise (no probing other people's cards). */
    private String owned(String userId, String paymentMethod) {
        var customer =
                customers.stripeCustomer(userId).orElseThrow(() -> new NotFound("payment_method", paymentMethod));
        if (cards.cards(customer).stream().noneMatch(c -> c.paymentMethod().equals(paymentMethod))) {
            throw new NotFound("payment_method", paymentMethod);
        }
        return customer;
    }

    private List<SavedCard> refresh(String userId, String customer) {
        var list = cards.cards(customer);
        var preferred = cards.defaultCard(customer);
        var fallback = list.isEmpty() ? null : list.getFirst().paymentMethod();
        var defaultId = preferred != null
                        && list.stream().anyMatch(c -> c.paymentMethod().equals(preferred))
                ? preferred
                : fallback;
        var saved = list.stream()
                .map(c -> new SavedCard(
                        c.paymentMethod(),
                        c.brand(),
                        c.last4(),
                        c.expMonth(),
                        c.expYear(),
                        c.paymentMethod().equals(defaultId),
                        c.created()))
                .toList();
        store.replace(userId, saved);
        return saved;
    }
}
