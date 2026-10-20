package ca.northline.booking.application;

import ca.northline.booking.api.CustomerPhotos;
import ca.northline.booking.application.CustomerPhotoStore.StoredPhoto;
import ca.northline.shared.Bytes;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ImageDecoding;
import ca.northline.shared.storage.ObjectKeys;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomerPhotos}: type by the declared content type <b>and</b> the file's signature, a readable image header
 * for JPEG/PNG (S-104's pixel ceiling, nothing decoded), at most 10 MB; the bytes go to object storage first (the
 * scanner's verdict), then the row. The business side reads through {@link ViewCustomerPhoto}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerPhotoService implements CustomerPhotos, ViewCustomerPhoto {

    static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp");
    private static final Set<String> RASTERS = Set.of("image/jpeg", "image/png");

    private final CustomerPhotoStore store;
    private final CustomerPhotoStorage storage;
    private final Clock clock;

    @Override
    @Transactional
    public Photo upload(String customerId, String fileName, String contentType, Bytes bytes) {
        if (bytes.isEmpty()) {
            throw RuleViolation.of("file", "required", REQUIRED);
        }
        var type = contentType.toLowerCase(Locale.ROOT);
        var raw = bytes.toArray();
        if (!TYPES.contains(type) || !signatureMatches(type, raw)) {
            throw RuleViolation.of("file", "format", TYPE);
        }
        if (bytes.size() > MAX_BYTES) {
            throw RuleViolation.of("file", "size", TOO_LARGE);
        }
        if (RASTERS.contains(type) && ImageDecoding.size(raw).isEmpty()) {
            throw RuleViolation.of("file", "format", TYPE);
        }
        var id = Ids.next();
        var name = fileName.isBlank() ? id : fileName.strip();
        var photo = new StoredPhoto(
                id,
                customerId,
                ObjectKeys.customerObject(customerId, id, type),
                type,
                bytes.size(),
                name.length() > 200 ? name.substring(name.length() - 200) : name,
                null,
                null,
                clock.instant());
        storage.put(photo.storageKey(), bytes, type);
        store.insert(photo);
        return view(photo);
    }

    @Override
    public Optional<Content> content(String customerId, String photoId) {
        return store.own(customerId, photoId).flatMap(this::content);
    }

    @Override
    public List<String> requireOwn(String customerId, List<String> photoIds) {
        var ids = photoIds.stream().distinct().toList();
        if (ids.size() > MAX_PER_JOB) {
            throw RuleViolation.of("photoIds", "size", TOO_MANY);
        }
        if (!ids.isEmpty() && store.attachable(customerId, ids).size() != ids.size()) {
            throw RuleViolation.of("photoIds", "allowed", GONE);
        }
        return ids;
    }

    @Override
    public Optional<Content> forMerchant(String merchantId, String photoId) {
        return store.visibleTo(merchantId, photoId).flatMap(this::content);
    }

    private Optional<Content> content(StoredPhoto p) {
        return storage.get(p.storageKey()).map(b -> new Content(b, p.contentType(), p.fileName()));
    }

    static Photo view(StoredPhoto p) {
        return new Photo(p.id(), p.fileName(), p.contentType(), p.byteSize());
    }

    /** The first bytes must be what the declared type says (a renamed file is refused). */
    static boolean signatureMatches(String type, byte[] b) {
        return switch (type) {
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
            case "image/png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/heic", "image/heif" ->
                b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            case "image/webp" ->
                b.length > 12
                        && b[0] == 'R'
                        && b[1] == 'I'
                        && b[2] == 'F'
                        && b[3] == 'F'
                        && b[8] == 'W'
                        && b[9] == 'E'
                        && b[10] == 'B'
                        && b[11] == 'P';
            default -> false;
        };
    }
}
