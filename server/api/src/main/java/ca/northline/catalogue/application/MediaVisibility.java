package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.MediaAsset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may see a listing image (S-123). Members of the uploading business always may; anyone else only once it is
 * approved content ({@link MediaRepository#approved}). The media endpoint enforces it, and the editor and the GTIN
 * lookup leave out images the caller could not load.
 */
@Component
@RequiredArgsConstructor
class MediaVisibility {

    private final MediaRepository media;

    boolean visibleTo(String merchantId, MediaAsset asset) {
        return merchantId.equals(asset.merchantId())
                || media.approved(List.of(asset.id())).contains(asset.id());
    }

    boolean approved(MediaAsset asset) {
        return media.approved(List.of(asset.id())).contains(asset.id());
    }

    List<MediaAsset> visibleTo(String merchantId, List<MediaAsset> assets) {
        var foreign = assets.stream()
                .filter(a -> !merchantId.equals(a.merchantId()))
                .map(MediaAsset::id)
                .toList();
        var approved = media.approved(foreign);
        return assets.stream()
                .filter(a -> merchantId.equals(a.merchantId()) || approved.contains(a.id()))
                .toList();
    }
}
