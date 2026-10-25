import { useSuspenseQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Skeleton, Tag, useLocale } from '@northline/ui';
import { serviceCategoryQuery, type ServiceCategory as Category } from './api';
import { price } from './format';
import { useServicesT } from './messages';
import { categoryName, copyKeys, familyOf, nouns } from './taxonomy';
import { dyn } from './text';
import { categorySlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { Crumbs } from '../pages/Crumbs';

/** design 06 `svcCategory`: what the category is, common jobs and typical prices, how booking works, get started. */
export function ServiceCategory({ slug }: { slug: string }) {
  const { locale } = useLocale();
  const { data } = useSuspenseQuery(serviceCategoryQuery(slug, locale));
  return <CategoryView category={data} />;
}

export function CategoryView({ category: c }: { category: Category }) {
  const t = useServicesT();
  const { locale } = useLocale();
  const name = categoryName(c.slug, c.names, locale);
  const family = familyOf(c.kind, c.slug, c.vehicle);
  const keys = copyKeys(family, c.kind);
  const noun = nouns(family, name.text, locale);
  const nameLower = locale === 'fr' || name.lang ? name.text : name.text.toLowerCase();
  return (
    <div className="nl-page nl-svc">
      <Crumbs label={t('breadcrumb')} items={[{ label: t('crumbHome'), href: '/' }, { label: t('crumbServices'), href: '/services' }, { label: name.text, lang: name.lang }]} />
      <section className="nl-band nl-svc-band" aria-labelledby="svc-cat-title">
        <div className="nl-band-copy">
          <h1 id="svc-cat-title" className="nl-band-title" lang={name.lang}>{name.text}</h1>
          <div className="nl-svc-tags">
            <Tag tone="neutral">{dyn(t, keys.kind)}</Tag>
            <Tag tone="neutral">{dyn(t, keys.where)}</Tag>
            <Tag tone="neutral">{t('verifiedProviders', { count: c.providers })}</Tag>
            {c.regulatedRegistry ? <Tag tone="success">{t('licenceChecked', { registry: c.regulatedRegistry })}</Tag> : null}
          </div>
          <p className="nl-band-lede">{dyn(t, keys.blurb, '', { registry: c.regulatedRegistry ?? 'none' })}</p>
        </div>
        <span className="nl-band-pic" aria-hidden><Scene slot={categorySlot(c.id) ?? 'banner/services'} subject={c.slug} fit="contain" sizes="(min-width: 960px) 45vw, 100vw" priority /></span>
      </section>
      <div className="nl-svc-split">
        <div className="nl-svc-main">
          <h2 className="nl-svc-h2">{t('commonJobs')}</h2>
          {c.jobs.length === 0
            ? <p className="nl-svc-muted">{c.quoteable ? t('noJobs') : t('noJobsFixed')}</p>
            : (
              <ul className="nl-svc-jobs">
                {c.jobs.map(j => (
                  <li key={j.name}>
                    <Link to="/services/$category/providers" params={{ category: c.slug }} className="nl-svc-job">
                      <span><strong>{j.name}</strong>{j.included ? <span className="nl-svc-job-desc">{j.included}</span> : null}</span>
                      <span className="nl-svc-job-price">{price(t, locale, j.pricingMode, j.priceCents)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}

          <h2 className="nl-svc-h2">{t('howBooking', { name: nameLower })}</h2>
          <ol className="nl-svc-steps">
            {([1, 2, 3, 4] as const).map(n => (
              <li key={n} className="nl-svc-step">
                <span className="nl-svc-step-n" aria-hidden>{n}</span>
                <div className="nl-svc-step-name">{dyn(t, keys.step(n).title)}</div>
                <div className="nl-svc-step-desc">{dyn(t, keys.step(n).desc)}</div>
              </li>
            ))}
          </ol>
        </div>
        <aside className="nl-svc-aside" aria-labelledby="svc-start">
          <h2 id="svc-start" className="nl-svc-aside-title">{t('getStarted')}</h2>
          <p className="nl-svc-aside-hint">{dyn(t, keys.ctaHint)}</p>
          <Link to="/services/$category/providers" params={{ category: c.slug }} className="btn btn-primary btn-lg nl-svc-cta">
            {c.providers > 0 ? t('seeProviders', { count: c.providers, noun: noun.cta }) : t('seeProvidersNone')}
          </Link>
          {c.quoteable
            ? <Link to="/services/$category/quote" params={{ category: c.slug }} className="btn btn-secondary nl-svc-cta2">{t('quoteCta')}</Link>
            : null}
          <div className="nl-svc-aside-note">{dyn(t, keys.note)}</div>
        </aside>
      </div>
    </div>
  );
}

export function ServiceCategorySkeleton() {
  return (
    <div className="nl-page nl-svc" aria-busy="true">
      <Skeleton width={220} height={12} />
      <div className="nl-band"><div className="nl-band-copy"><Skeleton width={220} height={28} /><Skeleton width="80%" height={14} /></div><span className="nl-band-pic" /></div>
      <div className="nl-svc-split">
        <div className="nl-svc-main nl-svc-skel-rows">
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={44} />)}
        </div>
        <Skeleton height={220} radius="var(--radius-panel)" />
      </div>
    </div>
  );
}
