package ca.northline.food.domain;

/**
 * Messages a customer sees when ordering food (S-57). The spec has no food-ordering section, so they are ours; English
 * on the server in both locales, mirrored in the consumer app (en + fr-CA).
 */
public final class FoodOrderMessages {
    private FoodOrderMessages() {}

    public static final String PICK_TWICE = "Choose each option once.";
    public static final String PICK_UNKNOWN = "That choice isn't on this dish any more. Open it again.";
    public static final String ADD_SOMETHING = "Add something to your order.";
    public static final String QTY = "Choose between 1 and 20.";
    public static final String TOO_MANY_LINES = "An order holds up to 30 lines.";
    public static final String NOTE_LONG = "Keep instructions under 140 characters.";
    public static final String COMBO_PICKS = "Choose every part of the combo.";
    public static final String COMBO_ITEM = "That dish isn't part of this combo.";

    public static String pickExactly(int n, String group) {
        return "Pick " + n + " for " + group + ".";
    }

    public static String pickAtLeast(int n, String group) {
        return "Pick at least " + n + " for " + group + ".";
    }

    public static String pickUpTo(int n, String group) {
        return "Pick up to " + n + " for " + group + ".";
    }

    public static String soldOut(String name) {
        return name + " is sold out.";
    }

    public static String belowMinimum(String missing) {
        return "Add " + missing + " to reach the $15 minimum.";
    }
}
