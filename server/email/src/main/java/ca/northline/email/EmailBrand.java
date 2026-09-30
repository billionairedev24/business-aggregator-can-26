package ca.northline.email;

import java.util.Map;

/**
 * Northline "Spruce &amp; Honey" for email clients. Email clients ignore stylesheets, CSS variables and
 * {@code color-mix()}, so this template layer is the one place outside {@code web/packages/tokens/tokens.json} that
 * holds colour literals (the web's no-hex rule covers components; docs/DECISIONS.md § S-13). The five base colours are
 * copied from tokens.json (a test keeps them equal); the few derived ones are sRGB approximations of the tokens'
 * {@code color-mix(in oklch, …)} ramps. Templates use {@link #styles()} only.
 */
public final class EmailBrand {

    // Base tokens (tokens.json base.*).
    public static final String ACCENT = "#1E4D36";
    public static final String ACCENT_2 = "#B4533A";
    public static final String HIGHLIGHT = "#D9A441";
    public static final String BG = "#F7F4EE";
    public static final String TEXT = "#15231B";

    // Derived (derived.css), approximated: surface = bg + 70 % white, divider = text 12 % over surface,
    // muted = neutral-600 (text 56 % over bg), highlight-100 = highlight 18 % over white, on-accent = white.
    public static final String SURFACE = "#FDFCFA";
    public static final String DIVIDER = "#E1E2DF";
    public static final String MUTED = "#787F78";
    public static final String HIGHLIGHT_100 = "#F8EFDD";
    public static final String ON_ACCENT = "#FFFFFF";

    // tokens.json font.* with email-safe fallbacks (web fonts load in few clients).
    public static final String FONT_HEADING = "Newsreader, Georgia, 'Times New Roman', serif";
    public static final String FONT_BODY =
            "'Instrument Sans', -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

    private static final Map<String, String> STYLES = Map.ofEntries(
            Map.entry("body", "margin:0;padding:0;background:" + BG + ";color:" + TEXT + ";font-family:" + FONT_BODY),
            Map.entry("outer", "background:" + BG + ";width:100%"),
            Map.entry("frame", "max-width:600px;width:100%"),
            Map.entry(
                    "brand",
                    "padding:8px 8px 16px;font-family:" + FONT_HEADING + ";font-size:24px;font-weight:500;color:"
                            + ACCENT),
            Map.entry(
                    "card",
                    "background:" + SURFACE + ";border:1px solid " + DIVIDER
                            + ";border-radius:12px;padding:32px 28px;color:" + TEXT + ";font-family:" + FONT_BODY
                            + ";font-size:15px;line-height:1.5"),
            Map.entry(
                    "h1",
                    "margin:0 0 12px;font-family:" + FONT_HEADING + ";font-size:26px;font-weight:500;line-height:1.2;"
                            + "color:" + TEXT),
            Map.entry("p", "margin:0 0 12px;font-size:15px;line-height:1.5;color:" + TEXT),
            Map.entry("small", "margin:0 0 8px;font-size:13px;line-height:1.5;color:" + MUTED),
            Map.entry(
                    "facts",
                    "width:100%;margin:8px 0 20px;border-collapse:collapse;font-size:15px;background:" + BG
                            + ";border-radius:8px"),
            Map.entry("factLabel", "padding:10px 14px;color:" + MUTED + ";border-bottom:1px solid " + DIVIDER),
            Map.entry(
                    "factValue",
                    "padding:10px 14px;text-align:right;font-weight:600;color:" + TEXT + ";border-bottom:1px solid "
                            + DIVIDER),
            Map.entry(
                    "notice",
                    "margin:0 0 16px;padding:12px 14px;background:" + HIGHLIGHT_100 + ";border-left:4px solid "
                            + HIGHLIGHT + ";border-radius:8px;font-size:14px;color:" + TEXT),
            Map.entry(
                    "warning",
                    "margin:0 0 16px;padding:12px 14px;background:" + BG + ";border-left:4px solid " + ACCENT_2
                            + ";border-radius:8px;font-size:14px;color:" + TEXT),
            Map.entry("buttonCell", "border-radius:999px;background:" + ACCENT),
            Map.entry(
                    "button",
                    "display:inline-block;padding:12px 24px;border-radius:999px;background:" + ACCENT + ";color:"
                            + ON_ACCENT + ";font-family:" + FONT_BODY
                            + ";font-size:15px;font-weight:600;text-decoration:none"),
            Map.entry("link", "color:" + ACCENT + ";text-decoration:underline;word-break:break-all"),
            Map.entry("footer", "padding:20px 8px 0;font-size:12px;line-height:1.5;color:" + MUTED),
            Map.entry("footerLink", "color:" + MUTED + ";text-decoration:underline"));

    private EmailBrand() {}

    /** Inline {@code style} attribute values by name ({@code ${s.button}} in templates). */
    public static Map<String, String> styles() {
        return STYLES;
    }
}
