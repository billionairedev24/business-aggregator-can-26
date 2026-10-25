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

    // palette color.* (brand.json "palette": plainsight, E-15 A · Plainsight with the S-188 brand layer: violet action,
    // yellow commerce with ink text — yellow is a fill, never text on white).
    public static final String ACCENT = "#5B34F0"; // primary (the action colour: the button, links; violet)
    public static final String ACCENT_2 = "#121418"; // accentInk (text on the commerce yellow)
    public static final String HIGHLIGHT = "#FFD23F"; // accent (commerce yellow: a fill only, never a button, a status or text)
    public static final String BG = "#F3F4F6"; // ground (the well)
    public static final String TEXT = "#121418"; // ink
    public static final String SURFACE = "#FFFFFF"; // surface
    public static final String DIVIDER = "#E4E7EB"; // border
    public static final String MUTED = "#434A55"; // textMuted
    public static final String HIGHLIGHT_100 = "#FFF3C4"; // accentTint
    public static final String ON_ACCENT = "#FFFFFF"; // primaryText
    public static final String WARNING = "#8F5600"; // warning

    // palette font.sans with email-safe fallbacks (web fonts load in few clients); headings use the same sans (E-15:
    // no display face, audit #5).
    public static final String FONT_BODY = "'IBM Plex Sans', -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    public static final String FONT_HEADING = FONT_BODY;

    private static final Map<String, String> STYLES = Map.ofEntries(
            Map.entry("body", "margin:0;padding:0;background:" + BG + ";color:" + TEXT + ";font-family:" + FONT_BODY),
            Map.entry("outer", "background:" + BG + ";width:100%"),
            Map.entry("frame", "max-width:600px;width:100%"),
            Map.entry(
                    "brand",
                    "padding:8px 8px 16px;font-family:" + FONT_HEADING + ";font-size:22px;font-weight:700;color:"
                            + TEXT),
            Map.entry(
                    "card",
                    "background:" + SURFACE + ";border:1px solid " + DIVIDER
                            + ";border-radius:4px;padding:32px 28px;color:" + TEXT + ";font-family:" + FONT_BODY
                            + ";font-size:15px;line-height:1.5"),
            Map.entry(
                    "h1",
                    "margin:0 0 12px;font-family:" + FONT_HEADING + ";font-size:24px;font-weight:700;line-height:1.2;"
                            + "color:" + TEXT),
            Map.entry("p", "margin:0 0 12px;font-size:15px;line-height:1.5;color:" + TEXT),
            Map.entry(
                    "code",
                    "margin:4px 0 16px;padding:12px 16px;background:" + BG + ";border:1px solid " + DIVIDER
                            + ";border-radius:3px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:28px;"
                            + "font-weight:600;letter-spacing:6px;text-align:center;color:" + TEXT),
            Map.entry("small", "margin:0 0 8px;font-size:13px;line-height:1.5;color:" + MUTED),
            Map.entry(
                    "facts",
                    "width:100%;margin:8px 0 20px;border-collapse:collapse;font-size:15px;background:" + BG
                            + ";border-radius:4px"),
            Map.entry("factLabel", "padding:10px 14px;color:" + MUTED + ";border-bottom:1px solid " + DIVIDER),
            Map.entry(
                    "factValue",
                    "padding:10px 14px;text-align:right;font-weight:600;color:" + TEXT + ";border-bottom:1px solid "
                            + DIVIDER),
            Map.entry(
                    "notice",
                    "margin:0 0 16px;padding:12px 14px;background:" + BG + ";border-left:4px solid "
                            + TEXT + ";border-radius:3px;font-size:14px;color:" + TEXT),
            Map.entry(
                    "warning",
                    "margin:0 0 16px;padding:12px 14px;background:" + BG + ";border-left:4px solid " + WARNING
                            + ";border-radius:3px;font-size:14px;color:" + TEXT),
            Map.entry("buttonCell", "border-radius:3px;background:" + ACCENT),
            Map.entry(
                    "button",
                    "display:inline-block;padding:12px 24px;border-radius:3px;background:" + ACCENT + ";color:"
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
