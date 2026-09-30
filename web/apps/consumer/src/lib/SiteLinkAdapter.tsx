import { useRouter } from '@tanstack/react-router';
import type { MouseEvent } from 'react';
import type { SiteLinkProps } from '@northline/ui';

/**
 * Kit links (header, account menu, pills) navigate with the router: a plain left click on a same-origin link is a
 * client-side navigation; modified clicks, new tabs and external links behave like any `<a>`.
 */
export function RouterSiteLink({ href, onClick, target, ...rest }: SiteLinkProps) {
  const router = useRouter();
  const click = (e: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(e);
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || target || !href.startsWith('/') || href.startsWith('//')) return;
    if (/\.(html|pdf|xml|txt)(?:[?#]|$)/.test(href)) return; // static files (legal pages) are not routes
    e.preventDefault();
    void router.navigate({ href });
  };
  return <a href={href} target={target} onClick={click} {...rest} />;
}
