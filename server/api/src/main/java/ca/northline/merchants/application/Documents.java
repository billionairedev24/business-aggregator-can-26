package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;

/** Uploading and reading merchant documents (legal documents, verification evidence, logos). */
public final class Documents {
    private Documents() {}

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final String FIELD = "file";
    public static final String TYPE_OR_SIZE = "Upload a PDF, PNG or JPEG under 10 MB.";
    public static final String LOGO_TYPE = "Upload an SVG or PNG under 10 MB.";
    public static final String EMPTY = "Choose a file to upload.";

    public interface UploadDocument {
        record Command(
                String merchantId,
                String actorId,
                Document.Purpose purpose,
                String fileName,
                String contentType,
                byte[] bytes) {}

        Document upload(Command command);
    }

    public interface ReadDocument {
        record Content(Document document, byte[] bytes) {}

        Content read(String merchantId, String documentId);
    }
}
