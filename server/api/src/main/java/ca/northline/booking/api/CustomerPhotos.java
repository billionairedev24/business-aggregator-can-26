package ca.northline.booking.api;

import ca.northline.shared.Bytes;
import java.util.List;
import java.util.Optional;

/**
 * S-153: photos a customer adds to a quote request or to a booking's job details ("photo of the leak", "the dent").
 * Uploaded first, attached when the request or booking is written. Object storage (the shared {@code ObjectStore} and
 * its virus-scanner hook); only the owner — and the businesses the request went to, or the booking's business — read
 * them.
 */
public interface CustomerPhotos {

    int MAX_PER_JOB = 6;
    long MAX_BYTES = 10L * 1024 * 1024;
    String REQUIRED = "Choose a photo to upload.";
    String TYPE = "Add a photo (JPEG, PNG, HEIC or WebP).";
    String TOO_LARGE = "Photos can be up to 10 MB.";
    String TOO_MANY = "Add up to 6 photos.";
    String GONE = "That photo is no longer available. Add it again.";

    record Photo(String id, String fileName, String contentType, long byteSize) {}

    record Content(Bytes bytes, String contentType, String fileName) {}

    /** Stores a photo of the customer's, not attached to anything yet. 422 on {@code file} for a bad one. */
    Photo upload(String customerId, String fileName, String contentType, Bytes bytes);

    /** The customer's own photo; empty for anyone else's. */
    Optional<Content> content(String customerId, String photoId);

    /**
     * Checks that every id is the customer's own photo, not attached to another job, at most {@link #MAX_PER_JOB}
     * (422 on {@code photoIds}); returns them de-duplicated in order.
     */
    List<String> requireOwn(String customerId, List<String> photoIds);
}
