package ca.northline.catalogue.application;

import ca.northline.shared.Bytes;
import java.net.URI;

/**
 * Outbound port (S-72): downloads an image a merchant's bulk-import file links to. The adapter follows the S-33 SSRF
 * rules (https only, public addresses only, pinned connection, no redirects) and a size and time cap.
 */
public interface RemoteImages {

    Result fetch(URI url);

    /** What came back: the bytes, a refusal before any request (not a public https URL), or why the fetch failed. */
    sealed interface Result {
        record Fetched(Bytes bytes) implements Result {}

        record Refused(String reason) implements Result {}

        record Unreachable(String reason) implements Result {}
    }
}
