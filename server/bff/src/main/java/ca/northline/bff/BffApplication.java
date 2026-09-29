package ca.northline.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * studio-bff (port 8082): the browser holds only an HttpOnly session cookie; OAuth tokens stay in the server-side
 * session (Redis in prod) and are relayed to the api for {@code /api/**}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffApplication {
    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
