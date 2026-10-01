package ca.northline.shared;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Immutable file content (an upload, a stored object, a photo). Use it instead of a {@code byte[]} record component:
 * records compare and print arrays by reference, this compares by content and prints only the size, so file content
 * never lands in a log. The array is copied in and out; uploads are capped at a few MB by every caller.
 */
@EqualsAndHashCode
@ToString
public final class Bytes {

    private static final Bytes EMPTY = new Bytes(new byte[0]);

    @ToString.Exclude
    private final byte[] content;

    private Bytes(byte[] content) {
        this.content = content;
    }

    public static Bytes of(byte[] bytes) {
        return bytes.length == 0 ? EMPTY : new Bytes(bytes.clone());
    }

    @ToString.Include(name = "size")
    public int size() {
        return content.length;
    }

    public boolean isEmpty() {
        return content.length == 0;
    }

    /** A copy of the content (a response body, a storage upload). */
    public byte[] toArray() {
        return content.clone();
    }
}
