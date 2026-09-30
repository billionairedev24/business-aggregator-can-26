package ca.northline.orders.domain;

/**
 * Cart and checkout messages (S-51). validation-rules.md has no cart section; the consumer app shows the same text
 * (en) and its fr-CA translation. Money-request messages (Idempotency-Key) are the Finance ones.
 */
public final class CheckoutMessages {
    private CheckoutMessages() {}

    public static final String QTY = "Choose a quantity from 1 to 99.";
    public static final String UNAVAILABLE = "This item isn't available any more.";
    public static final String CHOOSE_OPTION = "Choose an option.";
    public static final String SOLD_OUT = "This item is sold out.";
    public static final String ONLY_LEFT = "Only %d left.";
    public static final String GUEST = "Your browsing session expired. Reload the page.";
    public static final String STREET = "Enter the street address.";
    public static final String CITY = "Enter the city.";
    public static final String PROVINCE = "Choose a Canadian province or territory.";
    public static final String POSTAL = "Enter a Canadian postal code, like T2P 1B5.";
    public static final String UNIT = "Keep the unit under 20 characters.";
    public static final String NOTE = "Keep delivery notes under 200 characters.";
    public static final String ADDRESS = "Choose a delivery address.";
    public static final String WINDOW = "Choose a delivery window.";
    public static final String SUBSTITUTION = "Choose what we do if something's out of stock.";
    public static final String NOT_SERVED = "We don't deliver to %s yet.";
    public static final String SHOP_ELSEWHERE = "%s doesn't deliver to %s.";
    public static final String CART_EMPTY = "Your cart is empty.";
    public static final String WINDOW_CLOSED = "That delivery window just closed. Choose another.";
    public static final String OUT_OF_STOCK = "Something in your cart just sold out. Check your cart and try again.";
    public static final String NOT_AUTHORIZED = "The card payment isn't authorized yet.";
    public static final String EXPIRED = "This checkout expired. Check your cart and pay again.";
    public static final String STEP_UP = "Confirm it's you with your passkey or authenticator app to pay.";
    public static final String ENROL = "Add a passkey to pay: payments sit behind a second factor.";
}
