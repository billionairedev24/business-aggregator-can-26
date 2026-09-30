import { createContext, useContext, type AnchorHTMLAttributes, type ComponentType } from 'react';

/** A link the kit renders (header, menus); the app decides how it navigates (its router's client-side navigation). */
export type SiteLinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & { href: string };
export type SiteLinkComponent = ComponentType<SiteLinkProps>;

const PlainLink: SiteLinkComponent = props => <a {...props} />;
const LinkContext = createContext<SiteLinkComponent>(PlainLink);

/** Wrap the app so kit links navigate with the router instead of reloading the page. Plain `<a>` otherwise. */
export const SiteLinkProvider = LinkContext.Provider;

/** Renders an `<a href>` through the app's link component (SSR-friendly, crawlable, opens in a new tab normally). */
export function SiteLink(props: SiteLinkProps) {
  const Link = useContext(LinkContext);
  return <Link {...props} />;
}
