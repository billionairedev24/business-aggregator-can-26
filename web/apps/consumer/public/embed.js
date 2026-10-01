/*
 * Northline website embed (S-76). A business pastes, on its own website:
 *
 *   <script src="https://<northline site>/embed.js" data-store="<page slug>" data-key="pk_live_…" async></script>
 *
 * and gets its Book / Order button where the tag sits, linking to its Northline page. The publishable key is public:
 * it only names the business; the Studio can limit the websites it answers on. No cookies, no tracking, nothing stored.
 * Optional: data-lang="fr" (default: the page's <html lang>).
 */
(function () {
  'use strict';
  var LABELS = {
    en: { book_visit: 'Book a visit', request_quote: 'Request a quote', order_now: 'Order now', reserve: 'Reserve', on: 'on Northline' },
    fr: { book_visit: 'Réserver une visite', request_quote: 'Demander un devis', order_now: 'Commander', reserve: 'Réserver', on: 'sur Northline' },
  };

  function textOn(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#ffffff';
    var n = parseInt(m[1], 16);
    var c = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map(function (v) {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] > 0.4 ? '#111111' : '#ffffff';
  }

  function mount(script) {
    script.setAttribute('data-nl-mounted', '');
    var key = script.getAttribute('data-key');
    var store = script.getAttribute('data-store') || script.getAttribute('data-page');
    if (!key || !store) return;
    var site;
    try { site = new URL(script.src, document.baseURI).origin; } catch (e) { return; }
    var lang = (script.getAttribute('data-lang') || document.documentElement.lang || 'en').slice(0, 2).toLowerCase() === 'fr' ? 'fr' : 'en';
    var url = site + '/api/v1/public/embed?key=' + encodeURIComponent(key) + '&store=' + encodeURIComponent(store);
    return fetch(url, { credentials: 'omit', headers: { accept: 'application/json' } })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (page) {
        if (!page || !page.path) return;
        var t = LABELS[lang];
        var a = document.createElement('a');
        a.className = 'northline-embed';
        a.href = site + page.path;
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = t[page.ctaLabel] || t.book_visit;
        a.title = page.name + ' ' + t.on;
        a.setAttribute('data-northline-store', page.slug);
        a.style.cssText = 'display:inline-block;padding:12px 20px;border-radius:999px;font:600 15px/1.2 system-ui,sans-serif;'
          + 'text-decoration:none;background:' + page.brandColor + ';color:' + textOn(page.brandColor) + ';';
        script.parentNode && script.parentNode.insertBefore(a, script.nextSibling);
      })
      .catch(function () { /* the button stays away; the business's site is unaffected */ });
  }

  var scripts = document.querySelectorAll('script[data-key]:not([data-nl-mounted])');
  for (var i = 0; i < scripts.length; i++) {
    if (/\/embed\.js(\?|$)/.test(scripts[i].getAttribute('src') || '')) mount(scripts[i]);
  }
})();
