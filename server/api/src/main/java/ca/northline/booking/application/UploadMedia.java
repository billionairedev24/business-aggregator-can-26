package ca.northline.booking.application;

import ca.northline.booking.application.MediaCatalog.MediaInfo;
import ca.northline.shared.Bytes;

/** Upload a photo, diagram or PDF (quote attachments, completion photos). Images and PDF only, at most 10 MB. */
public interface UploadMedia {

    long MAX_BYTES = 10L * 1024 * 1024;
    String TYPE_NOT_ALLOWED = "Add a photo (JPEG, PNG, HEIC, WebP) or a PDF.";
    String TOO_LARGE = "Files can be at most 10 MB.";
    String EMPTY = "This file is empty.";

    record Command(String merchantId, String actorId, String fileName, String contentType, Bytes bytes) {}

    MediaInfo upload(Command command);
}
