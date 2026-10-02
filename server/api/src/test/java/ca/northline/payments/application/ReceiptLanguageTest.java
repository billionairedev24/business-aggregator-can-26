package ca.northline.payments.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.application.PaymentGateway.Authorization;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.region.api.FrenchListings;
import ca.northline.region.api.LanguageRules;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.MerchantPlaces.MerchantPlace;
import ca.northline.region.api.Regions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * S-116 (Loi 96 readiness): Stripe's receipts go out in French where the merchant's place is French-first (region
 * configuration) or the buyer reads French, else English — and Stripe is only told when it changes.
 */
class ReceiptLanguageTest {

    final EscrowRepository escrows = mock(EscrowRepository.class);
    final PaymentGateway gateway = mock(PaymentGateway.class);
    final MerchantPlaces places = mock(MerchantPlaces.class);
    final Regions regions = mock(Regions.class);
    final CheckoutPaymentService service = new CheckoutPaymentService(
            escrows,
            gateway,
            mock(TaxCalculationService.class),
            Clock.fixed(java.time.Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC),
            new PaymentMetrics(new SimpleMeterRegistry()),
            places,
            regions);

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    void place(boolean frenchFirst) {
        when(places.of("m1"))
                .thenReturn(new MerchantPlace("XF", true, "Effeville", "mkt-f", ZoneOffset.UTC, "Xf", "Xf", null));
        when(regions.languageRules("XF", "mkt-f"))
                .thenReturn(frenchFirst ? new LanguageRules(true, FrenchListings.REQUIRE) : LanguageRules.NONE);
        when(escrows.stripeCustomer("c1")).thenReturn(Optional.of("cus_1"));
        when(gateway.authorize(any()))
                .thenReturn(new Authorization(
                        "pi_1", IntentStatus.AUTHORIZED, 1000, 1000, "secret", "cus_1", null, null, null, "g1"));
    }

    void checkout() {
        service.start(new PaymentAuthorizations.Request("m1", "order", "o1", "c1", 1000, 0, "g1", null, null));
    }

    @Test
    void frenchFirstPlace_frenchReceiptsEvenForAnEnglishRequest() {
        place(true);
        LocaleContextHolder.setLocale(Locale.CANADA);
        when(escrows.receiptLocale("c1")).thenReturn(Optional.empty());
        checkout();
        verify(gateway).receiptLocale("cus_1", "fr-CA");
        verify(escrows).saveReceiptLocale("c1", "fr-CA");
    }

    @Test
    void elsewhere_theBuyersLanguage() {
        place(false);
        LocaleContextHolder.setLocale(Locale.CANADA_FRENCH);
        when(escrows.receiptLocale("c1")).thenReturn(Optional.of("en-CA"));
        checkout();
        verify(gateway).receiptLocale("cus_1", "fr-CA");
    }

    @Test
    void stripeIsOnlyCalledWhenTheLanguageChanges() {
        place(false);
        LocaleContextHolder.setLocale(Locale.CANADA);
        when(escrows.receiptLocale("c1")).thenReturn(Optional.of("en-CA"));
        checkout();
        verify(gateway, never()).receiptLocale(anyString(), anyString());
    }
}
