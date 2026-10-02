package ca.northline.email;

import java.net.URI;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.messageresolver.IMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders {@link EmailContent} in English or French: {@code email/templates/<template>.html} (inline styles from
 * {@link EmailBrand}, shared {@code layout.html}) and {@code .txt} (plain-text alternative, shared {@code footer.txt}),
 * copy from {@code email/messages[_fr].properties}. Every email ends with the CASL sender identification (mailing
 * address + contact) and why the address got it; {@link EmailContent.Purpose#NOTIFICATION} and
 * {@link EmailContent.Purpose#COMMERCIAL} emails also get the unsubscribe link, which is then mandatory.
 */
public final class EmailTemplates {

    static final String BUNDLE = "email/messages";

    private final TemplateEngine engine;
    private final String mailingAddress;
    private final String contact;
    private final ZoneId zone;

    /**
     * @param mailingAddress {@code northline.email.mailing-address} (the legal entity's address: configuration)
     * @param contact {@code northline.email.contact}
     * @param zone {@code northline.email.time-zone}: the zone dates and times are written in
     */
    public EmailTemplates(String mailingAddress, String contact, ZoneId zone) {
        this.mailingAddress = mailingAddress;
        this.contact = contact;
        this.zone = zone;
        var resolver = new ClassLoaderTemplateResolver(EmailTemplates.class.getClassLoader());
        resolver.setPrefix("email/templates/");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.getTextTemplateModePatternSpec().addPattern("*.txt");
        resolver.setCacheable(true);
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageResolver(new BundleMessages());
    }

    /**
     * @param unsubscribe one-click unsubscribe URL; required for notifications and commercial messages, ignored for
     *     transactional ones
     */
    public RenderedEmail render(EmailContent content, Locale locale, @Nullable URI unsubscribe) {
        if (content.purpose().needsUnsubscribe() && unsubscribe == null) {
            throw new IllegalArgumentException(
                    content.template() + " is a " + content.purpose() + " email: it needs an unsubscribe link (CASL)");
        }
        var language = EmailLocales.supported(locale);
        var format = EmailFormat.of(language, zone);
        var subjectKey = content.template() + ".subject" + (content.variant().isEmpty() ? "" : "." + content.variant());
        var subject = message(language, subjectKey, content.subjectArgs(format));
        var variables = new HashMap<String, Object>(content.variables(format));
        requireCodes(content.template(), variables);
        variables.put("subject", subject);
        variables.put("variant", content.variant());
        variables.put("purpose", content.purpose().name().toLowerCase(Locale.ROOT));
        variables.put("reason", message(language, content.template() + ".reason", content.reasonArgs()));
        variables.put("mailingAddress", mailingAddress);
        variables.put("contact", contact);
        variables.put(
                "unsubscribe",
                content.purpose().needsUnsubscribe() && unsubscribe != null ? unsubscribe.toString() : "");
        variables.put("lang", language.toLanguageTag());
        variables.put("s", EmailBrand.styles());
        var context = new Context(language, variables);
        var html = engine.process(content.template() + ".html", context);
        var text = engine.process(content.template() + ".txt", context).strip() + "\n";
        return new RenderedEmail(subject, html, text);
    }

    /**
     * S-104: the variables templates turn into message keys ({@code #{__${'payout-failed.heading.' + outcome}__}}).
     * Thymeleaf pre-processes {@code __…__} into the expression itself, so such a value must be a plain code — text
     * someone typed (a reviewer's reason, a note) there would be evaluated as an expression.
     */
    static final Set<String> KEY_VARIABLES =
            Set.of("decision", "phase", "change", "kind", "outcome", "role", "action", "rule");

    /** Lists whose items (or items' {@code code}) become message keys: {@code reasons}, {@code checks}. */
    static final Set<String> KEY_LISTS = Set.of("reasons", "checks");

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    static void requireCodes(String template, Map<String, Object> variables) {
        for (var name : KEY_VARIABLES) {
            var value = variables.get(name);
            // empty is fine (e.g. a dispute update before its decision); anything else must be a code
            if (value != null
                    && !String.valueOf(value).isEmpty()
                    && !CODE.matcher(String.valueOf(value)).matches()) {
                throw new IllegalArgumentException(template + ": " + name + " must be a code, not text");
            }
        }
        for (var name : KEY_LISTS) {
            if (variables.get(name) instanceof Collection<?> items) {
                for (var item : items) {
                    var code = item instanceof Map<?, ?> m ? m.get("code") : item;
                    if (code == null || !CODE.matcher(String.valueOf(code)).matches()) {
                        throw new IllegalArgumentException(template + ": every " + name + " item must be a code");
                    }
                }
            }
        }
    }

    /** A small standalone page in the email look ({@code email/templates/page-<name>.html}), e.g. unsubscribe. */
    public String page(String name, Locale locale, Map<String, Object> pageVariables) {
        var language = EmailLocales.supported(locale);
        var variables = new HashMap<String, Object>(pageVariables);
        variables.put("lang", language.toLanguageTag());
        variables.put("s", EmailBrand.styles());
        variables.put("mailingAddress", mailingAddress);
        variables.put("contact", contact);
        return engine.process("page-" + name + ".html", new Context(language, variables));
    }

    /** One message from the email bundle, formatted with {@link MessageFormat} in the given language. */
    public String message(Locale locale, String key, List<?> args) {
        var language = EmailLocales.supported(locale);
        return format(bundle(language).getString(key), language, args.toArray());
    }

    private static ResourceBundle bundle(Locale locale) {
        // No fallback to the JVM's default locale: English lives in the base file.
        return ResourceBundle.getBundle(
                BUNDLE, locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static String format(String pattern, Locale locale, @Nullable Object[] args) {
        return args == null || args.length == 0
                ? pattern.replace("''", "'")
                : new MessageFormat(pattern, locale).format(args);
    }

    /** {@code #{key(args)}} in templates → the bundle; a missing key fails the rendering. */
    private static final class BundleMessages implements IMessageResolver {

        @Override
        public String getName() {
            return "northline-email";
        }

        @Override
        public Integer getOrder() {
            return 0;
        }

        @Override
        public String resolveMessage(
                ITemplateContext context, Class<?> origin, String key, @Nullable Object[] messageParameters) {
            try {
                return format(bundle(context.getLocale()).getString(key), context.getLocale(), messageParameters);
            } catch (MissingResourceException e) {
                throw new IllegalStateException("Missing email message '" + key + "'", e);
            }
        }

        @Override
        public String createAbsentMessageRepresentation(
                ITemplateContext context, Class<?> origin, String key, @Nullable Object[] messageParameters) {
            throw new IllegalStateException("Missing email message '" + key + "'");
        }
    }
}
