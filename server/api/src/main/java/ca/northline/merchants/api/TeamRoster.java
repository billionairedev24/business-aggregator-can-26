package ca.northline.merchants.api;

import ca.northline.shared.security.MerchantRole;
import java.util.List;

/**
 * The team of a business and who customers can book (Availability › Calendar sync &amp; team). Added by the operations
 * workstream; implemented inside the merchants module over {@code merchants.merchant_members}.
 */
public interface TeamRoster {

    /** Members ordered owner first, then by join order. */
    List<TeamMember> members(String merchantId);

    /**
     * Shows or hides a member on the booking calendar.
     *
     * @throws ca.northline.shared.NotFound when the user is not a member of the business
     */
    void setBookable(String merchantId, String userId, boolean bookable);

    record TeamMember(String userId, MerchantRole role, boolean bookable) {}
}
