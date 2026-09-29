package ca.northline.merchants.domain;

import ca.northline.shared.RuleViolation;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Storefront address {@code northline.ca/<slug>}: {@code ^[a-z0-9-]{3,40}$} (V016 {@code chk_slug}). */
public record Slug(String value) {
    public static final String FIELD = "slug";
    public static final String FORMAT = "Use 3–40 lowercase letters, numbers or hyphens.";
    public static final String TAKEN = "That address is taken.";
    public static final int MAX = 40;

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9-]{3,40}$");

    public Slug {
        if (!PATTERN.matcher(value).matches()) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
    }

    /** "Pho Đậu Bò & Co." → {@code pho-dau-bo-and-co}; padded to 3 characters, cut at 40. */
    public static Slug from(String name) {
        var ascii = Normalizer.normalize(name.replace("đ", "d").replace("Đ", "D"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        var base = ascii.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.length() > MAX) {
            base = base.substring(0, MAX).replaceAll("-+$", "");
        }
        while (base.length() < 3) {
            base = base.isEmpty() ? "biz" : base + "-nl";
        }
        return new Slug(base.length() > MAX ? base.substring(0, MAX) : base);
    }

    /** {@code prairie-wrench-2}: a numbered variant that still fits 40 characters. */
    public Slug numbered(int n) {
        var suffix = "-" + n;
        var head = value.length() + suffix.length() > MAX ? value.substring(0, MAX - suffix.length()) : value;
        return new Slug(head.replaceAll("-+$", "") + suffix);
    }
}
