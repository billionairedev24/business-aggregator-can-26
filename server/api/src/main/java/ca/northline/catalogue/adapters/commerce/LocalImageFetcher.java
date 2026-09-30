package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.ImageFetcher;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * The fakes' images (S-35, {@code northline.commerce.provider=local}): a 1200 px product shot on white — a coloured
 * block whose colour comes from the file name — for {@code fixtures.northline.invalid}; nothing else is fetched.
 */
class LocalImageFetcher implements ImageFetcher {

    @Override
    public Optional<byte[]> fetch(URI url) {
        if (!FakeCatalogSource.IMAGE_HOST.equals(url.getHost())) {
            return Optional.empty();
        }
        var image = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1200, 1200);
            g.setColor(Color.getHSBColor((url.getPath().hashCode() & 0xff) / 255f, 0.55f, 0.6f));
            var inset = 250 + (url.getPath().length() * 7) % 100;
            g.fillRect(inset, inset, 1200 - 2 * inset, 1200 - 2 * inset);
        } finally {
            g.dispose();
        }
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Optional.of(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
