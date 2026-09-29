package ca.northline.food.domain;

/**
 * Validation messages of the kitchen screens. The spec (validation-rules.md) has no kitchen section; these are the
 * texts recorded in docs/DECISIONS.md › Kitchen and mirrored by the Studio's zod schemas. Where the onboarding
 * "First listings" form already shows a message for the same field, the same text is used.
 */
public final class KitchenMessages {
    private KitchenMessages() {}

    public static final String MENU_NAME = "Enter a menu name.";
    public static final String SECTION_NAME = "Enter a section name.";
    public static final String AT_MOST_40 = "At most 40 characters.";
    public static final String AT_MOST_60 = "At most 60 characters.";
    public static final String AT_MOST_80 = "At most 80 characters.";
    public static final String AT_MOST_500 = "At most 500 characters.";

    public static final String ITEM_NAME = "Enter a name.";
    public static final String PRICE = "Enter a price.";
    public static final String MENU_AND_SECTION = "Pick a menu and a section.";
    public static final String ALLERGENS = "Declare allergens, or choose None.";
    public static final String ALLERGEN_LIST = "Pick allergens from the Health Canada list.";
    public static final String DIETARY_LIST = "Pick dietary tags from the list.";
    public static final String OPTION = "Choose one of the options.";
    public static final String DAILY_LIMIT = "Enter a limit from 1 to 999.";
    public static final String MODIFIER_GROUPS = "Pick modifier groups from your list.";

    public static final String GROUP_NAME = "Enter a group name.";
    public static final String OPTIONS_MIN = "Add at least one option.";
    public static final String OPTION_NAME = "Enter an option name.";
    public static final String PICK_COUNT = "Enter a number from 1 to 20.";
    public static final String PRICE_DELTA = "Enter a price change from $0 to $100.";
    public static final String PICK_MORE_THAN_OPTIONS = "There aren't enough options to pick that many.";
    public static final String NESTED_OPTIONS = "Pick options from another group.";

    public static final String COMBO_NAME = "Enter a combo name.";
    public static final String SLOTS_MIN = "Add at least one slot.";
    public static final String SLOT_LABEL = "Describe this slot, e.g. Any 2 mains.";
    public static final String SLOT_QTY = "Enter a quantity from 1 to 20.";
    public static final String SLOT_ITEMS = "Pick a section or items for this slot.";
    public static final String DISCOUNT = "Enter a discount from 1 to 90 %.";
    public static final String COMBO_SAVING = "The combo must cost less than buying the items separately.";

    public static final String TIME_FORMAT = "Use a time like 11:00.";
    public static final String END_AFTER_START = "End time must be after start time.";
    public static final String OVERLAP = "These hours overlap another range on the same day.";
    public static final String DAYS = "Pick at least one day.";
    public static final String FUTURE_DATE = "Pick today or a later date.";
    public static final String NOTE_LENGTH = "At most 80 characters.";
    public static final String RADIUS = "Enter a radius from 1 to 25 km.";
    public static final String GROUP_MAX = "Enter a group size from 2 to 50.";
    public static final String SCHEDULED_DAYS = "Enter 1 to 14 days.";
    public static final String NOTICE_HOURS = "Enter a notice from 1 to 336 hours.";

    public static final String CSV_FILE = "Upload a CSV file under 1 MB.";
    public static final String CSV_HEADER = "The first row must name the columns: section, name, price, allergens.";
    public static final String CSV_EMPTY = "The file has no items.";
    public static final String CSV_TOO_MANY = "At most 500 items per file.";
    public static final String PHOTO_FILE = "Upload a JPEG, PNG or WebP photo under 10 MB.";
}
