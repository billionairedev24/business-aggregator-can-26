package ca.northline.booking.application;

import ca.northline.booking.application.MediaCatalog.MediaInfo;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class MediaService implements UploadMedia {

    private static final Set<String> ALLOWED =
            Set.of("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp", "application/pdf");

    private final MediaStore store;
    private final MediaCatalog catalog;
    private final Clock clock;

    @Override
    @Transactional
    public MediaInfo upload(Command command) {
        var type = command.contentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(type)) {
            throw RuleViolation.of("file", "content_type", TYPE_NOT_ALLOWED);
        }
        if (command.bytes().length == 0) {
            throw RuleViolation.of("file", "required", EMPTY);
        }
        if (command.bytes().length > MAX_BYTES) {
            throw RuleViolation.of("file", "length", TOO_LARGE);
        }
        var id = Ids.next();
        var key = store.put(command.merchantId(), id, type, command.bytes());
        var name =
                command.fileName().isBlank() ? "attachment" : command.fileName().strip();
        var media = new MediaInfo(
                id,
                command.merchantId(),
                name.length() > 200 ? name.substring(0, 200) : name,
                type,
                command.bytes().length,
                key,
                command.actorId(),
                clock.instant());
        catalog.insert(media);
        return media;
    }
}
