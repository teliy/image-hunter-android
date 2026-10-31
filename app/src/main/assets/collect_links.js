(() => {
  const urls = new Set();

  const decodeJson = (s) => String(s)
    .replace(/\\u002[fF]/g, '/')
    .replace(/\\\//g, '/');

  const push = (raw) => {
    try {
      if (!raw || typeof raw !== 'string' || raw.startsWith('data:')) return;
      const abs = new URL(raw, location.href);
      if (abs.protocol !== 'http:' && abs.protocol !== 'https:') return;
      // Прокси DuckDuckGo: раскрываем оригинальный URL из параметра u=
      if (abs.hostname.endsWith('duckduckgo.com') && abs.pathname.startsWith('/iu')) {
        const orig = abs.searchParams.get('u');
        if (orig) { push(orig); return; }
      }
      // Прокси Brave: /<подпись>/rs:fit:…/g:ce/<base64 оригинала>
      // (иконки фавиконов favicons.search.brave.com выбрасываем)
      if (abs.hostname === 'imgs.search.brave.com') {
        const pm = abs.pathname.match(/\/g:[a-z]+\/(.+)$/);
        if (pm) {
          try {
            let b64 = pm[1].replace(/\/+/g, '').replace(/-/g, '+').replace(/_/g, '/');
            b64 += '='.repeat((4 - b64.length % 4) % 4);
            const bin = atob(b64);
            const bytes = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
            const orig = new TextDecoder('utf-8').decode(bytes);
            if (/^https?:\/\//i.test(orig)
                && !new URL(orig).hostname.endsWith('favicons.search.brave.com')) {
              push(orig); return;
            }
          } catch (e) {}
        }
      }
      urls.add(abs.href);
    } catch (e) {}
  };

  const pushScan = (s) => {
    if (!s) return;
    (decodeJson(s).match(/https?:\/\/[^\s"'<>\\)]+/g) || []).forEach(push);
  };

  // 1. Все <img>
  document.querySelectorAll('img').forEach((im) => {
    push(im.currentSrc || im.src);
    if (im.srcset) push(im.srcset.split(',')[0].trim().split(/\s+/)[0]);
    if (im.dataset) {
      ['src', 'thumb', 'original', 'url', 'lazy-src', 'lazySrc'].forEach((k) => {
        if (im.dataset[k]) push(im.dataset[k]);
      });
    }
  });

  // 2. Яндекс: JSON в атрибуте data-bem (там прячутся ссылки на оригиналы)
  document.querySelectorAll('[data-bem]').forEach((el) => pushScan(el.getAttribute('data-bem')));

  // 2b. Sogou: настоящая картинка плитки — в атрибутах drag-img / data-img
  document.querySelectorAll('[drag-img], [data-img]').forEach((el) => {
    push(el.getAttribute('drag-img'));
    push(el.getAttribute('data-img'));
  });

  // 3. Анкоры, ведущие прямо на файлы картинок
  document.querySelectorAll('a[href]').forEach((a) => push(a.href));

  // 4. Сканируем сырой HTML: встроенный JSON выдачи содержит прямые ссылки
  pushScan(document.documentElement.outerHTML);

  return JSON.stringify(Array.from(urls).slice(0, 500));
})()
