package ca.northline.account.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wallet &amp; points (design 06 {@code wallet}): the points, eight weeks of earning and Northline Plus. */
public interface ViewWallet {

    record Wallet(Points points, @Nullable Plus plus) {}

    /** @param weekly points earned in each of the last eight weeks, oldest first */
    record Points(long balance, long valueCents, List<Long> weekly) {
        public Points {
            weekly = List.copyOf(weekly);
        }
    }

    /** @param plan {@code monthly | annual} */
    record Plus(
            String plan, @Nullable Instant since, @Nullable Instant renewsAt, int members) {}

    Wallet wallet(String userId);
}
