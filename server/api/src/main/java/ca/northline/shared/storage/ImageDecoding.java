package ca.northline.shared.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/**
 * Decoding uploaded or fetched images without letting a small file claim gigabytes (S-104, "decompression bomb"): a
 * PNG of a few kilobytes may declare 50 000 × 50 000 pixels, and {@code ImageIO.read} would allocate the whole raster
 * before anyone looks at the size. Here the dimensions come from the header first; an image over {@link #MAX_PIXELS}
 * is refused unread, and a decode for analysis is subsampled to at most {@link #ANALYSIS_PIXELS}.
 */
public final class ImageDecoding {

    /** Largest image accepted at all (100 megapixels — beyond any phone or product photo). */
    public static final long MAX_PIXELS = 100_000_000L;

    /** Largest raster decoded for analysis (colours, hashes): about 2 megapixels whatever the upload's size. */
    public static final long ANALYSIS_PIXELS = 2_000_000L;

    static {
        ImageIO.setUseCache(false); // no temp files under a read-only root file system
    }

    private ImageDecoding() {}

    /** Width and height from the image's header (no pixels decoded). */
    public record Size(int width, int height) {
        public long pixels() {
            return (long) width * height;
        }
    }

    /**
     * The dimensions of an image ImageIO can read, from its header only; empty when it isn't one, or when it is larger
     * than {@link #MAX_PIXELS}.
     */
    public static Optional<Size> size(byte[] bytes) {
        try {
            return withReader(bytes, reader -> {
                var size = new Size(reader.getWidth(0), reader.getHeight(0));
                return size.width() > 0 && size.height() > 0 && size.pixels() <= MAX_PIXELS
                        ? Optional.of(size)
                        : Optional.empty();
            });
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The image decoded for analysis — every {@code n}th pixel in each direction so that at most about
     * {@link #ANALYSIS_PIXELS} are held — or empty when it isn't a readable image within {@link #MAX_PIXELS}.
     */
    public static Optional<BufferedImage> forAnalysis(byte[] bytes) {
        try {
            return withReader(bytes, reader -> {
                var size = new Size(reader.getWidth(0), reader.getHeight(0));
                if (size.width() <= 0 || size.height() <= 0 || size.pixels() > MAX_PIXELS) {
                    return Optional.empty();
                }
                var step = subsampling(size);
                var param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return Optional.ofNullable(reader.read(0, param));
            });
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The smallest step that keeps the decoded raster within {@link #ANALYSIS_PIXELS}. */
    static int subsampling(Size size) {
        var step = 1;
        while ((long) Math.ceilDiv(size.width(), step) * Math.ceilDiv(size.height(), step) > ANALYSIS_PIXELS) {
            step++;
        }
        return step;
    }

    private interface ReaderCall<T> {
        Optional<T> apply(ImageReader reader) throws IOException;
    }

    private static <T> Optional<T> withReader(byte[] bytes, ReaderCall<T> call) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return Optional.empty();
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return call.apply(reader);
            } finally {
                reader.dispose();
            }
        }
    }
}
