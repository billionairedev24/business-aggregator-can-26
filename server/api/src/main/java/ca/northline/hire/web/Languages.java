package ca.northline.hire.web;

/** The reader's language for names ({@code ?lang=}): French when asked for, English otherwise. */
final class Languages {
    private Languages() {}

    static String of(String lang) {
        return lang.toLowerCase(java.util.Locale.ROOT).startsWith("fr") ? "fr" : "en";
    }
}
