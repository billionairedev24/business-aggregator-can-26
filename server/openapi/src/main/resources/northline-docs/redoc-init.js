// Redoc for the group in ?group= (default: the first). No inline script, so the page's CSP needs no hash.
(function () {
  var select = document.getElementById('group');
  var wanted = new URLSearchParams(location.search).get('group');
  for (var i = 0; i < select.options.length; i++) {
    if (select.options[i].getAttribute('data-group') === wanted) select.selectedIndex = i;
  }
  select.addEventListener('change', function () {
    var params = new URLSearchParams(location.search);
    params.set('group', select.options[select.selectedIndex].getAttribute('data-group'));
    location.search = params.toString();
  });
  var spruce = '#1E4D36', ink = '#15231B', rosehip = '#B4533A', honey = '#D9A441';
  Redoc.init(select.value, {
    expandResponses: '200,201',
    requiredPropsFirst: true,
    pathInMiddlePanel: true,
    jsonSampleExpandLevel: 2,
    scrollYOffset: '.masthead',
    hideDownloadButton: false,
    theme: {
      colors: {
        primary: { main: spruce }, error: { main: rosehip }, warning: { main: honey },
        text: { primary: ink }, http: { get: spruce, post: '#2F6F8F', put: '#8A5A00', patch: '#6B4E8A', delete: rosehip }
      },
      typography: {
        fontFamily: '"Instrument Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
        headings: { fontFamily: 'Newsreader, Georgia, serif', fontWeight: '600' },
        code: { fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace' }
      },
      sidebar: { backgroundColor: '#F7F4EE', textColor: ink },
      rightPanel: { backgroundColor: ink }
    }
  }, document.getElementById('redoc'), function (err) {
    if (err) {
      var box = document.getElementById('redoc-error');
      box.style.display = 'block';
      box.querySelector('span').textContent = String((err && err.message) || err);
    }
  });
})();
