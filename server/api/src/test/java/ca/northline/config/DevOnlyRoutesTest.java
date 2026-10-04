package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.config.web.DevOutboxController;
import ca.northline.email.EmailAddress;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import ca.northline.sms.SmsTransport;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.AnnotatedElement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-117: whatever exists only for a developer's machine and the end-to-end suite — routes under {@code /api/v1/dev}
 * (seed and payout-run endpoints, the outbox, the email previews, the fake Stripe Identity page) and the outbox's
 * recording wrappers — is absent under the deployed profiles, {@code prod} first.
 */
class DevOnlyRoutesTest {

    /** The profile sets the cloud environments run with (application-cloud.yml is the shared group). */
    static final List<Set<String>> DEPLOYED =
            List.of(Set.of("prod", "cloud"), Set.of("staging", "cloud"), Set.of("dev", "cloud"), Set.of());

    static List<Class<?>> devControllers() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("ca.northline").stream()
                        .filter(c -> c.isAnnotatedWith(RestController.class))
                        .<Class<?>>map(JavaClass::reflect)
                        .filter(type -> paths(type).anyMatch(p -> p.startsWith("/api/v1/dev")))
                        .toList();
    }

    /** Every path the controller maps: class-level prefixes combined with its methods' paths. */
    static Stream<String> paths(Class<?> type) {
        var prefixes = pathsOf(type);
        var methods = Stream.of(type.getDeclaredMethods())
                .flatMap(m -> Stream.of(pathsOf(m)))
                .toList();
        return prefixes.length == 0
                ? methods.stream()
                : Stream.of(prefixes)
                        .flatMap(p ->
                                Stream.concat(Stream.of(p), methods.stream().map(m -> p + m)));
    }

    private static String[] pathsOf(AnnotatedElement element) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) {
            return new String[0];
        }
        return mapping.path().length > 0 ? mapping.path() : mapping.value();
    }

    static boolean activeUnder(Class<?> type, Set<String> profiles) {
        var profile = AnnotatedElementUtils.findMergedAnnotation(type, Profile.class);
        return profile == null || Profiles.of(profile.value()).matches(profiles::contains);
    }

    @Test
    void everyDevRoute_isLocalOnly() {
        var controllers = devControllers();
        assertThat(controllers)
                .extracting(Class::getSimpleName)
                .contains(
                        "DevOutboxController",
                        "DevPayoutRunController",
                        "DevApprovalController",
                        "DevIdentitySessionController",
                        "EmailPreviewController");
        for (var controller : controllers) {
            assertThat(activeUnder(controller, Set.of("local")))
                    .as(controller.getSimpleName())
                    .isTrue();
            for (var profiles : DEPLOYED) {
                assertThat(activeUnder(controller, profiles))
                        .as("%s under %s", controller.getSimpleName(), profiles)
                        .isFalse();
            }
        }
    }

    @Test
    void theOutbox_isNotWiredUnderProd_andRecordsUnderLocal() {
        var sent = new ArrayList<String>();
        EmailSender email = m -> sent.add(m.subject());
        SmsTransport sms = new SmsTransport() {
            @Override
            public String sendText(String to, String body) {
                sent.add(body);
                return "id";
            }

            @Override
            public String call(String to, String spokenText, Locale locale) {
                return "id";
            }
        };
        var runner = new ApplicationContextRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(EmailSender.class, () -> email)
                .withBean(SmsTransport.class, () -> sms)
                .withUserConfiguration(DevOutboxConfig.class, DevOutboxController.class);

        runner.withPropertyValues("spring.profiles.active=prod,cloud").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(DevOutbox.class).doesNotHaveBean(DevOutboxController.class);
            assertThat(ctx.getBean(EmailSender.class)).isSameAs(email); // not wrapped
            assertThat(ctx.getBean(SmsTransport.class)).isSameAs(sms);
        });
        runner.withPropertyValues("spring.profiles.active=test")
                .run(ctx ->
                        assertThat(ctx).doesNotHaveBean(DevOutbox.class).doesNotHaveBean(DevOutboxController.class));

        runner.withPropertyValues("spring.profiles.active=local").run(ctx -> {
            assertThat(ctx).hasSingleBean(DevOutboxController.class);
            ctx.getBean(EmailSender.class)
                    .send(new EmailMessage(
                            new EmailAddress("Owner@Example.com", null), "Approved", "<p>Hi</p>", "Hi", Map.of(), "t"));
            ctx.getBean(SmsTransport.class).sendText("+15875550101", "Your code: 123456");
            var outbox = ctx.getBean(DevOutbox.class);
            assertThat(outbox.to("owner@example.com"))
                    .singleElement()
                    .satisfies(m -> assertThat(m.subject()).isEqualTo("Approved"));
            assertThat(outbox.to("(587) 555-0101"))
                    .singleElement()
                    .satisfies(m -> assertThat(m.text()).isEqualTo("Your code: 123456"));
            assertThat(sent).containsExactly("Approved", "Your code: 123456"); // still delivered
        });
    }
}
