package ca.northline.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * S-104: a "decompression bomb" — a PNG of a few hundred bytes that declares a raster of billions of pixels — is
 * refused from its header, before anything is allocated; real photos are measured exactly and analysed subsampled.
 */
public class ImageDecodingTest {

    @Test
    void aTinyFileClaimingAHugeRaster_isRefusedFromItsHeader() throws IOException {
        var bomb = bomb(60_000, 60_000);
        assertThat(bomb.length).isLessThan(1_000);

        assertThat(ImageDecoding.size(bomb)).isEmpty();
        assertThat(ImageDecoding.forAnalysis(bomb)).isEmpty();
    }

    @Test
    void aLargePhoto_isMeasuredExactly_andAnalysedSubsampled() throws IOException {
        var photo = png(3_000, 2_000);

        assertThat(ImageDecoding.size(photo)).contains(new ImageDecoding.Size(3_000, 2_000));
        var analysed = ImageDecoding.forAnalysis(photo).orElseThrow();
        assertThat((long) analysed.getWidth() * analysed.getHeight())
                .isLessThanOrEqualTo(ImageDecoding.ANALYSIS_PIXELS);
        assertThat(analysed.getWidth()).isEqualTo(1_500);
    }

    @Test
    void aSmallImage_isDecodedWhole_andNonImagesAreEmpty() throws IOException {
        assertThat(ImageDecoding.forAnalysis(png(40, 30)).orElseThrow().getWidth())
                .isEqualTo(40);
        assertThat(ImageDecoding.size("not an image".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(ImageDecoding.size(new byte[0])).isEmpty();
    }

    @Test
    void subsampling_keepsTheRasterWithinTheAnalysisBudget() {
        assertThat(ImageDecoding.subsampling(new ImageDecoding.Size(1_000, 1_000)))
                .isEqualTo(1);
        assertThat(ImageDecoding.subsampling(new ImageDecoding.Size(10_000, 10_000)))
                .isEqualTo(8);
    }

    /** A real PNG of this size (white). */
    static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A valid PNG header for {@code width × height} 8-bit grey pixels, followed by one compressed row only. */
    public static byte[] bomb(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(
                out,
                "IHDR",
                ByteBuffer.allocate(13)
                        .putInt(width)
                        .putInt(height)
                        .put((byte) 8) // bit depth
                        .put((byte) 0) // greyscale
                        .put((byte) 0)
                        .put((byte) 0)
                        .put((byte) 0)
                        .array());
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(new byte[width + 1]);
        deflater.finish();
        var compressed = new byte[width];
        var length = deflater.deflate(compressed);
        deflater.end();
        chunk(out, "IDAT", java.util.Arrays.copyOf(compressed, length));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
