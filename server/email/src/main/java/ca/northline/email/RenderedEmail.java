package ca.northline.email;

/** A template rendered for one recipient's language. */
public record RenderedEmail(String subject, String html, String text) {}
