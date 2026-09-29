package ca.northline.auth.signing;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;

/**
 * Key rotation for the {@code local} provider, without starting the server (docs/runbooks/key-rotation.md):
 *
 * <pre>
 * ./gradlew :auth:signingKeys --args='status'
 * ./gradlew :auth:signingKeys --args='rotate'                 # new key signs 10 min later; old one kept 1 h after
 * ./gradlew :auth:signingKeys --args='rotate --immediately'   # compromised key: switch now …
 * ./gradlew :auth:signingKeys --args='retire &lt;kid&gt;'           # … then drop it (its tokens stop verifying)
 * java -cp northline-auth.jar -Dloader.main=ca.northline.auth.signing.SigningKeysCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher rotate
 * </pre>
 *
 * Options: {@code --dir=…} (default {@code SIGNING_KEYS_DIR}, then {@code ~/.northline/auth-signing-keys}),
 * {@code --publish-ahead=10m}, {@code --retire-after=1h}. Running servers pick the change up by themselves.
 */
@Slf4j
public final class SigningKeysCommand {

    private SigningKeysCommand() {}

    public static void main(String[] args) {
        var options = Arrays.stream(args).filter(a -> a.startsWith("--")).toList();
        var words = new ArrayList<>(
                Arrays.stream(args).filter(a -> !a.startsWith("--")).toList());
        var dir = Path.of(option(options, "dir", defaultDir()));
        var keys = new LocalFileSigningKeys(
                dir,
                Clock.systemUTC(),
                DurationStyle.detectAndParse(option(options, "publish-ahead", "10m")),
                DurationStyle.detectAndParse(option(options, "retire-after", "1h")));
        var command = words.isEmpty() ? "status" : words.removeFirst();
        List<SigningKeys.KeyState> states = switch (command) {
            case "status" -> keys.describe();
            case "rotate" -> keys.rotate(options.contains("--immediately")).keys();
            case "retire" -> {
                if (words.isEmpty()) {
                    throw new IllegalArgumentException("retire needs a key id");
                }
                yield keys.retire(words.getFirst());
            }
            default ->
                throw new IllegalArgumentException(
                        "Unknown command " + command + ": status | rotate [--immediately] | retire <kid>");
        };
        log.info("Signing keys in {}:", keys.file());
        states.forEach(s -> log.info("  {} {} — {}", s.status(), s.keyId(), s.detail()));
    }

    private static String option(List<String> options, String name, String fallback) {
        var prefix = "--" + name + "=";
        return options.stream()
                .filter(o -> o.startsWith(prefix))
                .map(o -> o.substring(prefix.length()))
                .findFirst()
                .orElse(fallback);
    }

    private static String defaultDir() {
        var env = System.getenv("SIGNING_KEYS_DIR");
        return env != null && !env.isBlank()
                ? env
                : Path.of(Objects.requireNonNull(System.getProperty("user.home")), ".northline", "auth-signing-keys")
                        .toString();
    }
}
