package ca.northline.shared;

/** CAD money in cents. Persisted as {@code *_cents bigint}, serialized as {@code …Cents} longs. */
public record Money(long cents, String currency) {
    public Money {
        if (!"CAD".equals(currency)) {
            throw new IllegalArgumentException("CAD only");
        }
    }

    public static Money cad(long cents) {
        return new Money(cents, "CAD");
    }

    public Money plus(Money other) {
        return cad(Math.addExact(cents, other.cents));
    }
}
