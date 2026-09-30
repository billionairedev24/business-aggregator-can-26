package ca.northline.shared.crypto;

/** Wraps and unwraps data keys with a key that stays in the key service. One implementation per provider. */
interface KeyWrapper {

    /** The provider, for the start-up log. */
    String provider();

    Wrapped wrap(byte[] dataKey, String context);

    byte[] unwrap(String keyRef, byte[] wrappedKey, String context);

    @SuppressWarnings("ArrayRecordComponent")
    record Wrapped(String keyRef, byte[] wrappedKey) {}
}
