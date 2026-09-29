package ca.northline.shared.security;

/**
 * What a merchant-scoped endpoint needs. Pick the narrowest one that fits; {@link MerchantRole} maps roles to these.
 *
 * <table>
 *   <tr><th>permission</th><th>use for</th><th>roles</th></tr>
 *   <tr><td>VIEW</td><td>any read of the business (dashboard, lists, details)</td><td>all</td></tr>
 *   <tr><td>OPERATE</td><td>day-to-day work: jobs, orders, KDS, messages, complete + photo</td><td>owner, technician, cook</td></tr>
 *   <tr><td>EDIT</td><td>create/update catalogue, menu, availability, quotes</td><td>owner, technician, cook</td></tr>
 *   <tr><td>DELETE</td><td>delete records</td><td>owner</td></tr>
 *   <tr><td>FINANCE_READ</td><td>earnings, payouts, reports, tax documents</td><td>owner, bookkeeper</td></tr>
 *   <tr><td>MANAGE</td><td>business profile, storefront, team, payout/bank settings, API keys</td><td>owner</td></tr>
 * </table>
 */
public enum MerchantPermission {
    VIEW,
    OPERATE,
    EDIT,
    DELETE,
    FINANCE_READ,
    MANAGE
}
