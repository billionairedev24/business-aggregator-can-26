package ca.northline.merchants.application;

import org.jspecify.annotations.Nullable;

/** S-75: the consumer page reports that someone opened it; counts one visit for the business today. */
public interface CountStorefrontVisit {

    /** @throws ca.northline.shared.NotFound when the page isn't published (or the business isn't active) */
    void count(String slug, @Nullable String userAgent);
}
