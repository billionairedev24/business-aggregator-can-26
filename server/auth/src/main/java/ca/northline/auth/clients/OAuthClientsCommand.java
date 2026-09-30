package ca.northline.auth.clients;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Registers the configured OAuth clients without starting the auth server — for a Kubernetes Job or a one-off run
 * (docs/runbooks/README.md § OAuth clients):
 *
 * <pre>
 * ./gradlew :auth:oauthClients --args='list'        # configuration vs database, writes nothing
 * ./gradlew :auth:oauthClients --args='sync'        # create / update (never deletes)
 * java -cp northline-auth.jar -Dloader.main=ca.northline.auth.clients.OAuthClientsCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher sync
 * </pre>
 *
 * It reads the same configuration as the server (profile, environment variables, {@code application*.yml}), so give the
 * Job the auth Deployment's environment. Only a data source is started. Exits non-zero on an invalid configuration or
 * database error; any other argument ({@code --key=value}) is a Spring property.
 */
@Slf4j
public final class OAuthClientsCommand {

    private OAuthClientsCommand() {}

    public static void main(String[] args) {
        var words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        var command = words.isEmpty() ? "list" : words.getFirst();
        if (!command.equals("list") && !command.equals("sync")) {
            throw new IllegalArgumentException("Unknown command " + command + ": list | sync");
        }
        var outcomes = run(command, args);
        log.info("OAuth clients ({}):", command);
        outcomes.forEach(o -> log.info("  {}", o));
    }

    /** Runs {@code list} or {@code sync} in a data-source-only context and returns what it found or did. */
    static List<OAuthClientSync.Outcome> run(String command, String... args) {
        try (var context = new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(withArgument(args, "--northline.oauth.sync-on-startup=false"))) {
            var sync = context.getBean(OAuthClientSync.class);
            return command.equals("sync") ? sync.sync() : sync.plan();
        }
    }

    /** A command-line property outranks application.yml (default properties would not). */
    private static String[] withArgument(String[] args, String argument) {
        var all = Arrays.copyOf(args, args.length + 1);
        all[args.length] = argument;
        return all;
    }

    /**
     * Data source, JDBC and transactions only — no web server, Redis or signing keys. Deliberately not a
     * {@code @Configuration}: the auth server's component scan must not pick it up.
     */
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
    })
    @Import(OAuthClientsConfig.class)
    static class CommandContext {

        @Bean
        RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
            return new JdbcRegisteredClientRepository(jdbc);
        }
    }
}
