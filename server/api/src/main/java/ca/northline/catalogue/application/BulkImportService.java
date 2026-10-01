package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.AutomatedVetting;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.Gtin;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ImportBatch;
import ca.northline.catalogue.domain.ImportBatch.RowError;
import ca.northline.catalogue.domain.ImportBatch.ValidRow;
import ca.northline.catalogue.domain.ImportTemplate;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.Variant;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk upload. {@link #validate} reads the file and checks every row — nothing changes yet; errors come back with
 * row numbers. Image URLs are fetched under the S-33 SSRF rules and must be reachable JPG/PNG images of at least
 * 1000 px (S-72). {@link #commit} then creates new SKUs as drafts and updates existing SKUs with every column the row
 * fills in (S-72; an empty cell keeps the current value) — or price and stock only with the price & stock template.
 */
@Service
@RequiredArgsConstructor
@Transactional
class BulkImportService implements BulkImport {

    static final int MAX_ROWS = 10_000;
    static final String UNREADABLE = "Upload an .xlsx or .csv file.";
    static final String EMPTY = "The file has no rows.";
    static final String TOO_MANY = "Up to 10,000 rows per file.";
    // S-72: image URLs
    static final int MAX_IMAGE_URLS = 500;
    static final int PROBES_IN_FLIGHT = 8;
    static final String TOO_MANY_IMAGES = "Up to 500 image URLs per file.";
    static final String IMAGE_URLS_PER_ROW = "Up to 9 image URLs per row";
    static final String IMAGE_URL_FORMAT = "Image URL is not a valid link";
    static final String IMAGE_URL_NOT_ALLOWED = "Image URL must be a public https:// link";
    static final String IMAGE_URL_UNREACHABLE = "Image URL unreachable";
    static final String IMAGE_URL_NOT_IMAGE = "Image URL is not a JPG or PNG image";
    static final String IMAGE_URL_TOO_SMALL = "Image URL is under 1000 px on the longest side";

    private final SpreadsheetReader reader;
    private final ImportRepository imports;
    private final ListingRepository listings;
    private final CategoryCatalog categories;
    private final EditProduct editProduct;
    private final EditService editService;
    private final RemoteImages remoteImages;
    private final ImageInspector inspector;
    private final ManageMedia media;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── validate ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // S-72: no connection held while image URLs are fetched
    public ImportBatch validate(
            String merchantId, ImportTemplate template, String fileName, byte[] bytes, String actorId) {
        List<Map<String, String>> rows;
        try {
            rows = reader.read(fileName, bytes);
        } catch (SpreadsheetReader.UnreadableFile ex) {
            throw RuleViolation.of("file", "format", UNREADABLE);
        }
        rows = rows.stream()
                .filter(r -> r.values().stream().anyMatch(v -> !v.isBlank()))
                .toList();
        if (rows.isEmpty()) {
            throw RuleViolation.of("file", "required", EMPTY);
        }
        if (rows.size() > MAX_ROWS) {
            throw RuleViolation.of("file", "rows", TOO_MANY);
        }
        var errors = new ArrayList<RowError>();
        var valid = new ArrayList<ValidRow>();
        var seen = new HashSet<String>();
        var profiles = new HashMap<String, Optional<CategoryProfile>>();
        for (int i = 0; i < rows.size(); i++) {
            var row = new RowCheck(merchantId, template, i + 2, rows.get(i), seen, profiles);
            row.check().ifPresentOrElse(errors::add, () -> valid.add(row.toValid()));
        }
        probeImages(valid, errors);
        errors.sort(java.util.Comparator.comparingInt(RowError::row));
        var updates = (int) valid.stream().filter(ValidRow::update).count();
        var batch = new ImportBatch(
                Ids.next(),
                merchantId,
                fileName,
                template,
                rows.size(),
                valid.size() - updates,
                updates,
                errors,
                valid,
                ImportBatch.Status.VALIDATED,
                actorId,
                clock.instant(),
                null);
        imports.insert(batch);
        return batch;
    }

    /**
     * S-72: fetches every distinct image URL of the valid rows once (a few at a time) and turns a row whose image is
     * unreachable, refused by the SSRF rules, not a JPG/PNG or too small into an error row.
     */
    private void probeImages(List<ValidRow> valid, List<RowError> errors) {
        var urls = valid.stream().flatMap(r -> urlsOf(r).stream()).distinct().toList();
        if (urls.isEmpty()) {
            return;
        }
        if (urls.size() > MAX_IMAGE_URLS) {
            throw RuleViolation.of("file", "images", TOO_MANY_IMAGES);
        }
        var fetched = fetchAll(urls);
        var problems = new HashMap<String, String>();
        fetched.forEach((url, result) -> problem(result).ifPresent(p -> problems.put(url, p)));
        for (var it = valid.iterator(); it.hasNext(); ) {
            var row = it.next();
            var problem = urlsOf(row).stream()
                    .map(problems::get)
                    .filter(Objects::nonNull)
                    .findFirst();
            if (problem.isPresent()) {
                errors.add(new RowError(row.row(), row.sku(), problem.get()));
                it.remove();
            }
        }
    }

    private Optional<String> problem(RemoteImages.Result result) {
        return switch (result) {
            case RemoteImages.Result.Refused _ -> Optional.of(IMAGE_URL_NOT_ALLOWED);
            case RemoteImages.Result.Unreachable _ -> Optional.of(IMAGE_URL_UNREACHABLE);
            case RemoteImages.Result.Fetched f ->
                inspector
                        .inspect(f.bytes().toArray())
                        .map(facts -> Math.max(facts.width(), facts.height()) >= ListingMessages.IMAGE_MIN_PX
                                ? Optional.<String>empty()
                                : Optional.of(IMAGE_URL_TOO_SMALL))
                        .orElse(Optional.of(IMAGE_URL_NOT_IMAGE));
        };
    }

    /** Each URL fetched once, {@link #PROBES_IN_FLIGHT} at a time, each on its own virtual thread. */
    private Map<String, RemoteImages.Result> fetchAll(List<String> urls) {
        var results = new ConcurrentHashMap<String, RemoteImages.Result>();
        var gate = new Semaphore(PROBES_IN_FLIGHT);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var url : urls) {
                threads.execute(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        results.put(url, remoteImages.fetch(URI.create(url)));
                    } finally {
                        gate.release();
                    }
                });
            }
        }
        return results;
    }

    private static List<String> urlsOf(ValidRow row) {
        return splitUrls(row.values().getOrDefault("image_urls", ""));
    }

    /** Validation of one spreadsheet row; the first problem wins (one error per row in the report). */
    private final class RowCheck {
        private final String merchantId;
        private final ImportTemplate template;
        private final int rowNumber;
        private final Map<String, String> raw;
        private final java.util.Set<String> seen;
        private final Map<String, Optional<CategoryProfile>> profiles;
        private final Map<String, String> values = new LinkedHashMap<>();
        private String sku = "";
        private boolean update;
        /** The listing a row updates (its own SKU, or its parent's — S-72). */
        private @Nullable Listing existing;
        /** A row adding a variant to an existing listing: it needs a price and stock like a new listing. */
        private boolean newVariant;

        RowCheck(
                String merchantId,
                ImportTemplate template,
                int rowNumber,
                Map<String, String> raw,
                java.util.Set<String> seen,
                Map<String, Optional<CategoryProfile>> profiles) {
            this.merchantId = merchantId;
            this.template = template;
            this.rowNumber = rowNumber;
            this.raw = raw;
            this.seen = seen;
            this.profiles = profiles;
        }

        ValidRow toValid() {
            return new ValidRow(rowNumber, sku, update, values);
        }

        Optional<RowError> check() {
            sku = cell("sku");
            if (sku.isEmpty()) {
                return fail("Missing SKU");
            }
            if (!seen.add(sku.toLowerCase(Locale.ROOT))) {
                return fail("Duplicate SKU in file");
            }
            values.put("sku", sku);
            var existing = listings.bySku(merchantId, sku);
            update = existing.isPresent();
            this.existing = existing.orElse(null);
            return switch (template) {
                case PRICE_STOCK -> checkQuickUpdate(existing.orElse(null));
                case SERVICES ->
                    existing.isPresent() && existing.get().kind() != ListingKind.SERVICE
                            ? fail("SKU belongs to a product")
                            : checkService();
                case AUTO_PARTS, GROCERIES, CLOTHING ->
                    existing.isPresent() && existing.get().kind() != ListingKind.PRODUCT
                            ? fail("SKU belongs to a service")
                            : checkProduct();
            };
        }

        private Optional<RowError> checkQuickUpdate(@Nullable Listing listing) {
            if (listing == null) {
                return fail("Unknown SKU — add it with a category template");
            }
            var price = cell("price");
            var stock = cell("stock");
            if (price.isEmpty() && stock.isEmpty()) {
                return fail("Missing price and stock");
            }
            if (!price.isEmpty()) {
                var cents = money(price);
                if (cents.isEmpty()) {
                    return fail("Price must be an amount, e.g. 19.99");
                }
                values.put("price", Long.toString(cents.get()));
            }
            if (!stock.isEmpty()) {
                var units = wholeNumber(stock);
                if (units.isEmpty()) {
                    return fail("Stock must be a whole number");
                }
                values.put("stock", Integer.toString(units.get()));
            }
            return Optional.empty();
        }

        private Optional<RowError> checkService() {
            var name = cell("name");
            if (name.isEmpty() && !update) {
                return fail("Missing name");
            }
            values.put("name", name);
            var category = category(cell("category_id"));
            if (category.isEmpty() && !cell("category_id").isEmpty()) {
                return fail("Unknown category");
            }
            // S-72: an update keeps what the row leaves empty
            values.put("set.category_id", Boolean.toString(!cell("category_id").isEmpty()));
            values.put(
                    "set.pricing_mode", Boolean.toString(!cell("pricing_mode").isEmpty()));
            values.put("set.price", Boolean.toString(!cell("price").isEmpty()));
            values.put(
                    "set.duration_min", Boolean.toString(!cell("duration_min").isEmpty()));
            values.put("set.buffer_min", Boolean.toString(!cell("buffer_min").isEmpty()));
            values.put("set.included", Boolean.toString(!cell("included").isEmpty()));
            values.put(
                    "set.instant_book", Boolean.toString(!cell("instant_book").isEmpty()));
            if (category.isPresent()
                    && (!category.get().isService() || !category.get().leaf())) {
                return fail("Category not allowed in Services");
            }
            category.ifPresent(c -> values.put("category_id", c.id()));
            var mode = cell("pricing_mode").isEmpty()
                    ? "fixed"
                    : cell("pricing_mode").toLowerCase(Locale.ROOT);
            if (!List.of("fixed", "quote", "hourly").contains(mode)) {
                return fail("Pricing mode must be fixed, quote or hourly");
            }
            values.put("pricing_mode", mode);
            if (!"quote".equals(mode) && !(update && cell("price").isEmpty())) {
                var problem = price(category.orElse(null));
                if (problem.isPresent()) {
                    return problem;
                }
            }
            var duration = cell("duration_min").isEmpty() ? Optional.of(60) : wholeNumber(cell("duration_min"));
            if (duration.isEmpty() || duration.get() < 15 || duration.get() > 720) {
                return fail("Duration must be 15–720 minutes");
            }
            values.put("duration_min", duration.get().toString());
            var buffer = cell("buffer_min").isEmpty() ? Optional.of(0) : wholeNumber(cell("buffer_min"));
            if (buffer.isEmpty() || buffer.get() > 120) {
                return fail("Buffer must be 0–120 minutes");
            }
            values.put("buffer_min", buffer.get().toString());
            values.put("included", cell("included"));
            var instant = cell("instant_book").toLowerCase(Locale.ROOT);
            values.put(
                    "instant_book",
                    Boolean.toString(!List.of("no", "false", "0", "n").contains(instant)));
            return Optional.empty();
        }

        private Optional<RowError> checkProduct() {
            var parent = cell("parent_sku");
            if (!parent.isEmpty()) {
                if (update) {
                    return fail("SKU already exists");
                }
                var existingParent = listings.bySku(merchantId, parent);
                if (existingParent.isPresent()) {
                    if (!(existingParent.get() instanceof ProductListing p)) {
                        return fail("SKU belongs to a service");
                    }
                    // S-72: re-importing a listing with variants updates it — the row's variant, or a new one
                    update = true;
                    existing = p;
                    values.put("parent_listing", p.getId());
                    var known = p.getDetails().variants().stream()
                            .anyMatch(v -> v.sku().equalsIgnoreCase(sku));
                    newVariant = !known;
                    values.put("new_variant", Boolean.toString(newVariant));
                }
                values.put("parent_sku", parent);
            }
            var title = cell("title");
            if (title.isEmpty() && !update) {
                return fail("Missing title");
            }
            values.put("title", title);
            var gtin = cell("gtin");
            if (!gtin.isEmpty()) {
                var problem = Gtin.problem("gtin", gtin);
                if (problem.isPresent()) {
                    return fail(problem.get().message());
                }
                values.put("gtin", Gtin.normalize(gtin));
            }
            values.put("brand", cell("brand"));
            values.put("mpn", cell("mpn"));
            var current = existing == null ? null : existing.categoryId();
            var categoryId = !cell("category_id").isEmpty()
                    ? cell("category_id")
                    : current != null
                            ? current
                            : java.util.Objects.requireNonNullElse(template.defaultCategoryId(), "");
            var category = category(categoryId);
            if (category.isEmpty()) {
                return fail("Unknown category");
            }
            var cat = category.get();
            if (!cat.isShop() || !cat.leaf() || cat.banned()) {
                return fail("Category not allowed in Shop");
            }
            values.put("category_id", cat.id());
            values.put("set.category_id", Boolean.toString(!cell("category_id").isEmpty()));
            for (var spec : cat.attributes()) {
                var column = snake(spec.key());
                var value = cell(column);
                if (value.isEmpty()) {
                    if (spec.required() && !update) {
                        return fail("Category \"%s\" requires attribute %s".formatted(cat.name(), spec.label()));
                    }
                    continue;
                }
                var option = spec.options().stream()
                        .filter(o -> o.equalsIgnoreCase(value))
                        .findFirst();
                if (option.isEmpty()) {
                    return fail("%s must be one of %s".formatted(spec.label(), String.join(", ", spec.options())));
                }
                values.put("attr." + spec.key(), option.get());
            }
            var keepEmpty = update && !newVariant; // an update keeps what the row leaves empty
            if (!(keepEmpty && cell("price").isEmpty())) {
                var priceProblem = price(cat);
                if (priceProblem.isPresent()) {
                    return priceProblem;
                }
            }
            var stock = cell("stock");
            if (!(keepEmpty && stock.isEmpty())) {
                var units = wholeNumber(stock);
                if (stock.isEmpty() || units.isEmpty()) {
                    return fail(stock.isEmpty() ? "Missing stock" : "Stock must be a whole number");
                }
                values.put("stock", units.get().toString());
            }
            values.put("size", cell("size"));
            values.put("colour", cell("colour"));
            return imageUrls();
        }

        /** S-72: {@code image_urls} — up to 9 links, main first, separated by spaces, new lines or "|". */
        private Optional<RowError> imageUrls() {
            var urls = splitUrls(cell("image_urls"));
            if (urls.size() > ListingMessages.IMAGES_MAX) {
                return fail(IMAGE_URLS_PER_ROW);
            }
            for (var url : urls) {
                if (parseUrl(url).isEmpty()) {
                    return fail(IMAGE_URL_FORMAT);
                }
            }
            if (!urls.isEmpty()) {
                values.put("image_urls", String.join(" ", urls));
            }
            return Optional.empty();
        }

        private Optional<RowError> price(@Nullable CategoryProfile category) {
            var price = cell("price");
            if (price.isEmpty()) {
                return fail("Missing price");
            }
            var cents = money(price);
            if (cents.isEmpty() || cents.get() <= 0) {
                return fail("Price must be an amount, e.g. 19.99");
            }
            var median = category == null ? null : category.medianPriceCents();
            if (median != null && AutomatedVetting.isOutlier(cents.get(), median)) {
                var pct = AutomatedVetting.deviationPercent(cents.get(), median);
                return fail("Price %s is %d%% %s category median — confirm"
                        .formatted(dollars(cents.get()), Math.abs(pct), pct < 0 ? "below" : "above"));
            }
            values.put("price", cents.get().toString());
            return Optional.empty();
        }

        private Optional<CategoryProfile> category(String id) {
            return id.isEmpty() ? Optional.empty() : profiles.computeIfAbsent(id, categories::profile);
        }

        private String cell(String column) {
            return raw.getOrDefault(column, "").strip();
        }

        private Optional<RowError> fail(String message) {
            return Optional.of(new RowError(rowNumber, sku.isEmpty() ? null : sku, message));
        }
    }

    // ── commit ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public ImportBatch commit(String merchantId, String importId, String actorId) {
        var batch = imports.find(merchantId, importId).orElseThrow(() -> new NotFound("import", importId));
        var done = batch.imported(clock.instant());
        var now = clock.instant();
        // S-72: the images validated a moment ago are fetched again and stored as the business's own uploads
        var urls = batch.pendingRows().stream()
                .flatMap(r -> urlsOf(r).stream())
                .distinct()
                .toList();
        var images = urls.isEmpty() ? Map.<String, RemoteImages.Result>of() : fetchAll(urls);
        var creates = new LinkedHashMap<String, List<ValidRow>>();
        var withVariants = new LinkedHashMap<String, List<ValidRow>>();
        for (var row : batch.pendingRows()) {
            var parentListing = row.values().get("parent_listing");
            if (parentListing != null) {
                withVariants
                        .computeIfAbsent(parentListing, _ -> new ArrayList<>())
                        .add(row);
            } else if (!row.update()) {
                creates.computeIfAbsent(row.values().getOrDefault("parent_sku", row.sku()), _ -> new ArrayList<>())
                        .add(row);
            } else if (batch.template() == ImportTemplate.PRICE_STOCK) {
                applyUpdate(merchantId, row, actorId, now);
            } else if (batch.template() == ImportTemplate.SERVICES) {
                updateService(merchantId, row, actorId);
            } else {
                updateProduct(merchantId, row.sku(), null, List.of(row), upload(merchantId, row, images), actorId);
            }
        }
        withVariants.forEach((listingId, rows) ->
                updateProduct(merchantId, null, listingId, rows, upload(merchantId, rows.getFirst(), images), actorId));
        creates.forEach((sku, rows) -> {
            if (batch.template() == ImportTemplate.SERVICES) {
                editService.create(new EditService.Command(merchantId, null, service(rows.getFirst()), actorId));
            } else {
                editProduct.create(new EditProduct.Command(
                        merchantId, null, product(sku, rows, upload(merchantId, rows.getFirst(), images)), actorId));
            }
        });
        imports.update(done);
        return done;
    }

    /** The row's images as the business's own uploads, main first; an image that no longer loads is left out. */
    private List<String> upload(String merchantId, ValidRow row, Map<String, RemoteImages.Result> images) {
        var ids = new ArrayList<String>();
        for (var url : urlsOf(row)) {
            if (images.get(url) instanceof RemoteImages.Result.Fetched f) {
                try {
                    ids.add(media.upload(merchantId, f.bytes().toArray()).id());
                } catch (RuleViolation e) {
                    // changed since validation (no longer an image, too small): imported without it
                }
            }
        }
        return ids;
    }

    /** Price & stock template: price and stock only. A new price on an approved listing goes back to vetting (S-39). */
    private void applyUpdate(String merchantId, ValidRow row, String actorId, java.time.Instant now) {
        var listing = listings.bySku(merchantId, row.sku()).orElseThrow(() -> new NotFound("listing", row.sku()));
        var price = row.values().get("price");
        var stock = row.values().get("stock");
        switch (listing) {
            case ProductListing p -> {
                var revet = p.restock(
                        price == null ? p.getDetails().priceCents() : Long.parseLong(price),
                        stock == null ? p.getDetails().stock() : Integer.parseInt(stock),
                        actorId,
                        now);
                listings.save(p);
                revet.forEach(events::publishEvent);
            }
            case ServiceListing s -> {
                if (price != null) {
                    var revet = s.reprice(Long.parseLong(price), actorId, now);
                    listings.save(s);
                    revet.forEach(events::publishEvent);
                }
            }
        }
    }

    /** S-72: a services-template row for an existing SKU updates every column it fills in; empty cells keep the value. */
    private void updateService(String merchantId, ValidRow row, String actorId) {
        var listing = listings.bySku(merchantId, row.sku()).orElseThrow(() -> new NotFound("listing", row.sku()));
        if (!(listing instanceof ServiceListing s)) {
            return; // validation refused a product SKU in the services template
        }
        var d = s.getDetails();
        var v = row.values();
        var mode = set(v, "pricing_mode")
                ? ca.northline.shared.CodedEnum.fromCode(PricingMode.class, v.getOrDefault("pricing_mode", "fixed"))
                : d.pricingMode();
        var price = set(v, "price") ? Long.valueOf(v.getOrDefault("price", "0")) : d.priceCents();
        var details = new ServiceDetails(
                text(v.get("name"), d.name()),
                set(v, "category_id") ? blankToNull(v.get("category_id")) : d.categoryId(),
                mode,
                mode == PricingMode.QUOTE ? null : price,
                set(v, "duration_min") ? Integer.parseInt(v.getOrDefault("duration_min", "60")) : d.durationMin(),
                set(v, "buffer_min") ? Integer.parseInt(v.getOrDefault("buffer_min", "0")) : d.bufferMin(),
                set(v, "included") ? blankToNull(v.get("included")) : d.included(),
                set(v, "instant_book") ? Boolean.parseBoolean(v.getOrDefault("instant_book", "true")) : d.instantBook(),
                d.sku());
        editService.update(new EditService.Command(merchantId, s.getId(), details, actorId));
    }

    /**
     * S-72: re-import of an existing product — its own SKU ({@code rows} = that row), or the variant rows of an
     * existing parent listing. Every column a row fills in replaces the listing's value (title, GTIN, brand, MPN,
     * category, attributes, price, stock, images); variant rows update the variant with their SKU or add one. Saved
     * through the editor's use case, so the editor's rules, re-vetting (S-39) and events apply.
     */
    private void updateProduct(
            String merchantId,
            @Nullable String sku,
            @Nullable String listingId,
            List<ValidRow> rows,
            List<String> uploaded,
            String actorId) {
        var listing = listingId != null
                ? listings.product(merchantId, listingId).orElseThrow(() -> new NotFound("listing", listingId))
                : listings.bySku(merchantId, java.util.Objects.requireNonNull(sku))
                        .filter(ProductListing.class::isInstance)
                        .map(ProductListing.class::cast)
                        .orElseThrow(() -> new NotFound("listing", sku));
        var d = listing.getDetails();
        var v = rows.getFirst().values();
        var attributes = new HashMap<>(d.attributes());
        v.forEach((k, val) -> {
            if (k.startsWith("attr.")) {
                attributes.put(k.substring(5), val);
            }
        });
        var variants = d.variants();
        var theme = d.variantTheme();
        long price;
        int stock;
        if (listingId != null) {
            variants = mergedVariants(d.variants(), rows);
            if (theme == VariantTheme.NONE) {
                theme = VariantTheme.SIZE;
            }
            price = variants.stream().mapToLong(Variant::priceCents).min().orElse(d.priceCents());
            stock = variants.stream().mapToInt(Variant::stock).sum();
        } else {
            price = v.containsKey("price") ? Long.parseLong(v.get("price")) : d.priceCents();
            stock = v.containsKey("stock") ? Integer.parseInt(v.get("stock")) : d.stock();
        }
        var newGtin = listingId != null ? null : blankToNull(v.get("gtin"));
        var identifierType =
                newGtin != null && d.identifierType() == IdentifierType.NONE ? IdentifierType.GTIN : d.identifierType();
        var details = new ProductDetails(
                identifierType,
                newGtin != null ? newGtin : d.gtin(),
                text(v.get("title"), d.title()),
                nonBlank(v.get("brand"), d.brand()),
                nonBlank(v.get("mpn"), d.mpn()),
                set(v, "category_id") ? v.get("category_id") : d.categoryId(),
                attributes,
                d.description(),
                d.bullets(),
                theme,
                variants,
                uploaded.isEmpty() ? d.imageSource() : ImageSource.OWN,
                uploaded.isEmpty() ? d.ownImageIds() : uploaded,
                d.sku(),
                price,
                d.compareAtCents(),
                d.costCents(),
                d.condition(),
                stock,
                d.lowStockAt(),
                d.fulfilment(),
                d.handlingTime(),
                d.returnsPolicy(),
                d.countryOfOrigin(),
                d.restrictedOk(),
                d.bilingualOk(),
                d.warranty(),
                d.searchKeywords());
        editProduct.update(new EditProduct.Command(merchantId, listing.getId(), details, actorId));
    }

    /** The listing's variants with each row's variant updated (matched by SKU) or added at the end. */
    private static List<Variant> mergedVariants(List<Variant> current, List<ValidRow> rows) {
        var merged = new ArrayList<>(current);
        for (var row : rows) {
            var v = row.values();
            var at = -1;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).sku().equalsIgnoreCase(row.sku())) {
                    at = i;
                }
            }
            if (at < 0) {
                merged.add(new Variant(
                        null,
                        variantName(v),
                        row.sku(),
                        blankToNull(v.get("gtin")),
                        Long.parseLong(v.getOrDefault("price", "0")),
                        Integer.parseInt(v.getOrDefault("stock", "0"))));
            } else {
                var old = merged.get(at);
                var named = !v.getOrDefault("size", "").isEmpty()
                        || !v.getOrDefault("colour", "").isEmpty();
                merged.set(
                        at,
                        new Variant(
                                old.id(),
                                named ? variantName(v) : old.value(),
                                old.sku(),
                                nonBlank(v.get("gtin"), old.gtin()),
                                v.containsKey("price") ? Long.parseLong(v.get("price")) : old.priceCents(),
                                v.containsKey("stock") ? Integer.parseInt(v.get("stock")) : old.stock()));
            }
        }
        return merged;
    }

    private static boolean set(Map<String, String> values, String column) {
        return Boolean.parseBoolean(values.get("set." + column));
    }

    private static String text(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static @Nullable String nonBlank(@Nullable String value, @Nullable String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ServiceDetails service(ValidRow row) {
        var v = row.values();
        var mode = ca.northline.shared.CodedEnum.fromCode(PricingMode.class, v.getOrDefault("pricing_mode", "fixed"));
        var price = v.get("price");
        return new ServiceDetails(
                v.getOrDefault("name", ""),
                blankToNull(v.get("category_id")),
                mode,
                price == null ? null : Long.parseLong(price),
                Integer.parseInt(v.getOrDefault("duration_min", "60")),
                Integer.parseInt(v.getOrDefault("buffer_min", "0")),
                blankToNull(v.get("included")),
                Boolean.parseBoolean(v.getOrDefault("instant_book", "true")),
                row.sku());
    }

    private static ProductDetails product(String sku, List<ValidRow> rows, List<String> imageIds) {
        var first = rows.getFirst().values();
        var grouped = first.containsKey("parent_sku");
        var attributes = new HashMap<String, String>();
        first.forEach((k, val) -> {
            if (k.startsWith("attr.")) {
                attributes.put(k.substring(5), val);
            }
        });
        var hasSize =
                rows.stream().anyMatch(r -> !r.values().getOrDefault("size", "").isEmpty());
        var hasColour = rows.stream()
                .anyMatch(r -> !r.values().getOrDefault("colour", "").isEmpty());
        var theme = !grouped
                ? VariantTheme.NONE
                : hasSize && hasColour ? VariantTheme.SIZE_COLOUR : hasColour ? VariantTheme.COLOUR : VariantTheme.SIZE;
        var variants = grouped
                ? rows.stream()
                        .map(r -> new Variant(
                                null,
                                variantName(r.values()),
                                r.sku(),
                                blankToNull(r.values().get("gtin")),
                                Long.parseLong(r.values().getOrDefault("price", "0")),
                                Integer.parseInt(r.values().getOrDefault("stock", "0"))))
                        .toList()
                : List.<Variant>of();
        var gtin = grouped ? null : blankToNull(first.get("gtin"));
        return new ProductDetails(
                gtin == null ? IdentifierType.NONE : IdentifierType.GTIN,
                gtin,
                first.getOrDefault("title", ""),
                blankToNull(first.get("brand")),
                blankToNull(first.get("mpn")),
                blankToNull(first.get("category_id")),
                attributes,
                null,
                List.of(),
                theme,
                variants,
                gtin == null || !imageIds.isEmpty() ? ImageSource.OWN : ImageSource.SHARED,
                imageIds,
                sku,
                Long.parseLong(first.getOrDefault("price", "0")),
                null,
                null,
                ItemCondition.NEW,
                rows.stream()
                        .mapToInt(r -> Integer.parseInt(r.values().getOrDefault("stock", "0")))
                        .sum(),
                null,
                List.of(Fulfilment.POOLED),
                null,
                null,
                null,
                false,
                false,
                false,
                null);
    }

    private static String variantName(Map<String, String> v) {
        var parts = new ArrayList<String>();
        for (var key : List.of("size", "colour")) {
            var val = v.getOrDefault(key, "");
            if (!val.isEmpty()) {
                parts.add(val);
            }
        }
        return parts.isEmpty() ? v.getOrDefault("sku", "") : String.join(" · ", parts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportBatch> history(String merchantId) {
        return imports.history(merchantId, 50);
    }

    // ── parsing helpers ────────────────────────────────────────────────────────────────────────────────────────────

    static Optional<Long> money(String text) {
        try {
            var amount = new BigDecimal(text.replace("$", "").replace(",", "").strip());
            return amount.scale() > 2 || amount.signum() < 0
                    ? Optional.empty()
                    : Optional.of(amount.movePointRight(2).longValueExact());
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    static Optional<Integer> wholeNumber(String text) {
        try {
            var n = new BigDecimal(text.strip());
            return n.signum() < 0 || n.stripTrailingZeros().scale() > 0
                    ? Optional.empty()
                    : Optional.of(n.intValueExact());
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    static String dollars(long cents) {
        return cents % 100 == 0 ? "$" + cents / 100 : "$%d.%02d".formatted(cents / 100, cents % 100);
    }

    /** S-72: the links of an {@code image_urls} cell — separated by spaces, new lines or "|". */
    static List<String> splitUrls(String cell) {
        return java.util.Arrays.stream(cell.split("[\\s|]+"))
                .map(String::strip)
                .filter(u -> !u.isEmpty())
                .toList();
    }

    /** An absolute http(s) URL with a host, or empty (the SSRF rules then decide whether it may be fetched). */
    static Optional<URI> parseUrl(String text) {
        try {
            var uri = new URI(text);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null
                    ? Optional.of(uri)
                    : Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
