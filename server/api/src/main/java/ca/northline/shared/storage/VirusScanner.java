package ca.northline.shared.storage;

/**
 * Hook every upload passes before it reaches object storage ({@link ObjectStore#put}). The default is {@link #NONE}
 * (accepts everything, logs nothing): declare a {@code VirusScanner} bean (ClamAV over clamd, a cloud malware-scanning
 * API, …) to turn scanning on — it replaces the default. {@link Verdict.Infected} rejects the upload with a 422 on the
 * {@code file} field and nothing is stored; an exception thrown by the scanner fails the upload (fail closed). Under
 * {@code STORAGE_PROVIDER=local} the modules' disk fakes do not call it.
 */
@FunctionalInterface
public interface VirusScanner {

    /** Accepts every file. */
    VirusScanner NONE = (_, _, _) -> new Verdict.Clean();

    Verdict scan(String key, String contentType, byte[] bytes);

    /** Outcome of a scan. */
    sealed interface Verdict {
        record Clean() implements Verdict {}

        /** @param threat the scanner's name for what it found (logged, never shown to the user) */
        record Infected(String threat) implements Verdict {}
    }
}
