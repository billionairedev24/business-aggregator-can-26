package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.ImageInspector;
import ca.northline.shared.storage.ImageDecoding;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * JDK ImageIO ({@link ImageDecoding}): accepts JPG and PNG (by magic bytes), measures size, whether the border is near-white (main image on
 * pure white) and a 64-bit average hash (8×8 greyscale, one bit per pixel above the mean) for the duplicate index.
 */
@Component
class ImageIoInspector implements ImageInspector {

    /** Channel value that still counts as white. */
    static final int WHITE = 242;
    /** Share of border samples that must be white. */
    static final double WHITE_SHARE = 0.95;

    @Override
    public Optional<ImageFacts> inspect(byte[] bytes) {
        var type = contentType(bytes);
        if (type == null) {
            return Optional.empty();
        }
        // S-104: dimensions from the header, pixels subsampled — a small file can't claim a gigabyte raster
        var size = ImageDecoding.size(bytes);
        if (size.isEmpty()) {
            return Optional.empty();
        }
        return ImageDecoding.forAnalysis(bytes)
                .map(image -> new ImageFacts(
                        type, size.get().width(), size.get().height(), onWhite(image), averageHash(image)));
    }

    static @org.jspecify.annotations.Nullable String contentType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    /** Samples the outer 3 % band of the image. */
    static boolean onWhite(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int band = Math.max(1, Math.min(w, h) * 3 / 100);
        int step = Math.max(1, Math.max(w, h) / 200);
        long samples = 0, white = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (x >= band && x < w - band && y >= band && y < h - band) {
                    continue;
                }
                samples++;
                int rgb = image.getRGB(x, y);
                int alpha = rgb >>> 24;
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
                if (alpha < 16 || (r >= WHITE && g >= WHITE && bl >= WHITE)) {
                    white++;
                }
            }
        }
        return samples > 0 && white >= WHITE_SHARE * samples;
    }

    static long averageHash(BufferedImage image) {
        var small = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        var g = small.createGraphics();
        try {
            g.drawImage(image.getScaledInstance(8, 8, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        } finally {
            g.dispose();
        }
        var pixels = new int[64];
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            pixels[i] = small.getRaster().getSample(i % 8, i / 8, 0);
            sum += pixels[i];
        }
        var mean = sum / 64.0;
        long hash = 0;
        for (int i = 0; i < 64; i++) {
            hash = (hash << 1) | (pixels[i] > mean ? 1 : 0);
        }
        return hash;
    }
}
