package ca.northline.email;

import java.util.Map;

/**
 * The brand for email clients. Email clients ignore stylesheets, CSS variables and {@code color-mix()}, so this
 * template layer is the one place outside {@code web/packages/tokens} that holds colour literals (the web's no-hex rule
 * covers components; docs/DECISIONS.md § S-13). Every colour is a copy of the palette that
 * {@code web/packages/tokens/brand.json} selects ({@code palettes/<name>.json}); a test keeps them equal, so switching
 * the palette fails the build until these follow (redesign, docs/design/redesign-spec.md § 5). Templates use
 * {@link #styles()} only.
 */
public final class EmailBrand {

    // palette color.* (brand.json "palette": evergreen).
    public static final String ACCENT = "#1F5E4A"; // primary
    public static final String ACCENT_2 = "#8A3612"; // accentInk
    public static final String HIGHLIGHT = "#C4531C"; // accent
    public static final String BG = "#FAF8F4"; // ground
    public static final String TEXT = "#1C1B19"; // ink
    public static final String SURFACE = "#FFFFFF"; // surface
    public static final String DIVIDER = "#E7E2D9"; // border
    public static final String MUTED = "#57534E"; // textMuted
    public static final String HIGHLIGHT_100 = "#F8E6DA"; // accentTint
    public static final String ON_ACCENT = "#FFFFFF"; // primaryText

    // palette font.sans and font.display with email-safe fallbacks (web fonts load in few clients).
    public static final String FONT_BODY = "'DM Sans', -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    public static final String FONT_HEADING = "Fraunces, Georgia, 'Times New Roman', serif";

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
