package ca.northline.tools.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The demo marketplace the seed builds ({@code demo-seed/plan.json} next to these classes): accounts, businesses with
 * their listings or menus, and an application for the console's queue. No place names: the market comes from the
 * region configuration at run time.
 */
record DemoPlan(
        Map<String, Account> accounts,
        Map<String, String> postalCodes,
        List<String> reviewers,
        List<Application> applications,
        List<Business> businesses) {

    static final String RESOURCE = "/demo-seed/plan.json";

    DemoPlan {
        accounts = Map.copyOf(accounts);
        postalCodes = Map.copyOf(postalCodes);
        reviewers = List.copyOf(reviewers);
        applications = List.copyOf(applications);
        businesses = List.copyOf(businesses);
    }

    record Account(
            String first,
            String last,
            String email,
            @Nullable List<String> roles,
            String app,
            String role,
            @Nullable Address address) {

        String displayName() {
            return first + " " + last;
        }

        List<String> platformRoles() {
            return roles == null ? List.of() : roles;
        }
    }

    record Address(String street, @Nullable String unit, String label) {}

    record Application(String key, String type, String name, List<String> categories, String about, String colour) {}

    record Business(
            String key,
            String type,
            String name,
            List<String> categories,
            String colour,
            String tier,
            String tagline,
            String about,
            List<Listing> listings,
            @Nullable String photo,
            @Nullable String owner,
            @Nullable String sameDayCutoff,
            @Nullable String licence,
            @Nullable List<String> cuisines,
            @Nullable String hours,
            @Nullable List<MenuSection> menu) {

        boolean provider() {
            return "provider".equals(type);
        }

        boolean seller() {
            return "seller".equals(type);
        }

        boolean kitchen() {
            return "kitchen".equals(type);
        }

        List<MenuSection> menuSections() {
            return menu == null ? List.of() : menu;
        }
    }

    record Listing(
            String key,
            String title,
            @Nullable Long priceCents,
            @Nullable Integer durationMin,
            @Nullable Integer stock,
            @Nullable String unit,
            String text,
            @Nullable String photo,
            @Nullable String category,
            @Nullable String pricing,
            @Nullable Long compareAtCents) {

        String categoryOr(Business business) {
            return category == null ? business.categories().getFirst() : category;
        }

        String pricingMode() {
            return pricing == null ? "fixed" : pricing;
        }
    }

    record MenuSection(String name, List<Dish> items) {}

    record Dish(String name, long priceCents, String text, List<String> allergens, List<String> dietary) {}

    static DemoPlan load() {
        try (InputStream in = DemoPlan.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE + " on the classpath");
            }
            return JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readValue(in, DemoPlan.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Account account(String key) {
        var a = accounts.get(key);
        if (a == null) {
            throw new IllegalStateException("plan.json has no account " + key);
        }
        return a;
    }
}
