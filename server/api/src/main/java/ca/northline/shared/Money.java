package ca.northline.shared;

public record Money(long cents, String currency) {
    public Money { if (!"CAD".equals(currency)) throw new IllegalArgumentException("CAD only"); }
    public static Money cad(long cents) { return new Money(cents, "CAD"); }
}
