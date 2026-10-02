package ca.northline.shared.crypto;

import java.util.regex.Pattern;

/**
 * Where a module stores {@link SecretSealer.Sealed} values, declared as a bean by the module that owns the table
 * (S-115) so {@link KeyRewrap} can re-wrap them after a key rotation without knowing the module. The context a value
 * was sealed with must be {@code contextPrefix + <id column>}.
 *
 * @param table {@code schema.table}
 * @param id the row's key column
 * @param keyRef the column holding {@link SecretSealer.Sealed#keyRef()}
 * @param wrappedKey the wrapped data key column
 * @param ciphertext the ciphertext column (only read for "is there a value")
 * @param contextPrefix what the module puts before the id in the sealing context (often empty)
 */
public record SealedColumn(
        String table, String id, String keyRef, String wrappedKey, String ciphertext, String contextPrefix) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

    public SealedColumn {
        for (var name : new String[] {table, id, keyRef, wrappedKey, ciphertext}) {
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("Not a plain SQL identifier: " + name);
            }
        }
    }

    String context(String rowId) {
        return contextPrefix + rowId;
    }
}
