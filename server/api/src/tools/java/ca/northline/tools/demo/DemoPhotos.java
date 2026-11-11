package ca.northline.tools.demo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Photos for the demo's listings, from the photo library (web/packages/media/source, docs/runbooks/photos.md): a slot
 * ({@code demo/listing/bakery.sourdough}, {@code category/service.home-trades.plumber}) is its JPEG under {@code
 * DEMO_PHOTOS_DIR} (the image ships them in /app/tools/demo-photos; a checkout reads the library itself). Each is
 * written to the api's media store at the key a real upload gets ({@code <merchantId>/<mediaId>.jpg}, under {@code
 * catalogue/} in object storage), once: an object already there is left alone. A slot without a photo (the library's
 * designed fallbacks) gives no image, so the listing shows the designed photo well — never a drawn picture.
 */
@Slf4j
final class DemoPhotos implements AutoCloseable {

    record Photo(String storageKey, int width, int height, int bytes) {}

    private interface Sink extends AutoCloseable {
        boolean exists(String key);

        void put(String key, byte[] bytes);

        @Override
        default void close() {}
    }

    private final Path library;
    private final Sink sink;
    private int written;

    private DemoPhotos(Path library, Sink sink) {
        this.library = library;
        this.sink = sink;
    }

    /** The media store the api uses ({@code STORAGE_PROVIDER}): s3, or a local folder ({@code local}, the default). */
    static DemoPhotos open(Map<String, String> env) {
        var library = Path.of(env.getOrDefault("DEMO_PHOTOS_DIR", "/app/tools/demo-photos"));
        if (!Files.isDirectory(library)) {
            throw new IllegalStateException("no photo library at " + library + " (DEMO_PHOTOS_DIR)");
        }
        var provider = env.getOrDefault("STORAGE_PROVIDER", "local").strip();
        return switch (provider) {
            case "s3" -> new DemoPhotos(library, s3(env));
            case "local", "" -> new DemoPhotos(library, local(env));
            default ->
                throw new IllegalStateException("STORAGE_PROVIDER=" + provider + ": the demo seed writes photos to s3 or a local folder only");
        };
    }

    /** Stores the slot's photo for a listing; empty when the library has no photo in that slot. */
    Optional<Photo> store(String slot, String merchantId, String mediaId) {
        var file = library.resolve(slot + ".jpg").normalize();
        if (!file.startsWith(library) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            var bytes = Files.readAllBytes(file);
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            var key = merchantId + "/" + mediaId + ".jpg";
            if (!sink.exists(key)) {
                sink.put(key, bytes);
                written++;
            }
            return Optional.of(new Photo(
                    key, image == null ? 1200 : image.getWidth(), image == null ? 1200 : image.getHeight(), bytes.length));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean has(String slot) {
        return Files.isRegularFile(library.resolve(slot + ".jpg"));
    }

    int written() {
        return written;
    }

    @Override
    public void close() throws Exception {
        sink.close();
    }

    private static Sink local(Map<String, String> env) {
        var root = Path.of(env.getOrDefault(
                        "NORTHLINE_MEDIA_LOCALDIR", Path.of(System.getProperty("java.io.tmpdir"), "northline-media").toString()))
                .toAbsolutePath()
                .normalize();
        log.info("Photos into the local media folder {}", root);
        return new Sink() {
            @Override
            public boolean exists(String key) {
                return Files.isRegularFile(root.resolve(key));
            }

            @Override
            public void put(String key, byte[] bytes) {
                try {
                    var file = root.resolve(key);
                    Files.createDirectories(file.getParent());
                    Files.write(file, bytes);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private static Sink s3(Map<String, String> env) {
        var bucket = env.getOrDefault("STORAGE_BUCKET", "");
        if (bucket.isBlank()) {
            throw new IllegalStateException("STORAGE_PROVIDER=s3 without STORAGE_BUCKET");
        }
        var builder = S3Client.builder()
                .region(Region.of(nonBlank(env.get("STORAGE_REGION"), "ca-central-1")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(Boolean.parseBoolean(env.getOrDefault("STORAGE_PATH_STYLE", "false")))
                        .build());
        var endpoint = env.get("STORAGE_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.strip()));
        }
        var access = env.get("STORAGE_ACCESS_KEY");
        var secret = env.get("STORAGE_SECRET_KEY");
        builder.credentialsProvider(
                access != null && !access.isBlank() && secret != null
                        ? StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))
                        : DefaultCredentialsProvider.builder().build());
        var s3 = builder.build();
        var prefix = "catalogue/";
        log.info("Photos into bucket {} under {}", bucket, prefix);
        return new Sink() {
            @Override
            public boolean exists(String key) {
                try {
                    s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(prefix + key).build());
                    return true;
                } catch (NoSuchKeyException e) {
                    return false;
                } catch (S3Exception e) {
                    if (e.statusCode() == 404) {
                        return false;
                    }
                    throw e;
                }
            }

            @Override
            public void put(String key, byte[] bytes) {
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(prefix + key)
                                .contentType("image/jpeg")
                                .build(),
                        RequestBody.fromBytes(bytes));
            }

            @Override
            public void close() {
                s3.close();
            }
        };
    }

    private static String nonBlank(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
