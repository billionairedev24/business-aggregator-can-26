package ca.northline.merchants.domain;

import ca.northline.shared.RuleViolation;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Storefront brand colour: {@code #rrggbb} that passes WCAG AA (4.5:1) with white text (validation-rules.md ›
 * Storefront). The builder offers a curated swatch set; custom colours are rejected when they fail.
 */
public record BrandColor(String hex) {
    public static final String FIELD = "brandColor";
    public static final String FORMAT = "Pick a colour like #2F5D3A.";
    public static final String CONTRAST = "White text needs 4.5:1 contrast — pick a darker colour.";
    public static final double MIN_CONTRAST = 4.5;
    /** Design 02 swatch 0 ("Forest"). */
    public static final String DEFAULT = "#2f5d3a";

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public BrandColor {
        if (!HEX.matcher(hex).matches()) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
        hex = hex.toLowerCase(Locale.ROOT);
        if (contrastWithWhite(hex) < MIN_CONTRAST) {
            throw RuleViolation.of(FIELD, "contrast", CONTRAST);
        }
    }

    /** WCAG 2.x contrast ratio between {@code #rrggbb} and white. */
    public static double contrastWithWhite(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double l = 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
        return 1.05 / (l + 0.05);
    }

    private static double channel(int value) {
        double c = (value & 0xFF) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
